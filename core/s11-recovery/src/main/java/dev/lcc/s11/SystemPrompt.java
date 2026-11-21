package dev.lcc.s11;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * s11 的全部新东西：system prompt 的分段拼装。
 *
 * 前面九篇往 system 里塞了三样东西：身份、工具策略、技能目录（s07）、记忆目录（s09）。
 * 塞的办法一直是字符串相加：SYSTEM + memory.indexSection() + skills.catalogSection()。
 * 能跑，但三件事混在一起之后就答不上来两个问题：
 *   加了新东西该放第几段？       —— 取决于它多久变一次
 *   这段变了，前面攒的缓存还算数吗？ —— 取决于它前面还有没有别的东西会变
 *
 * 所以这里把它拆成段：每段带一个「变化频率」和段内序号，拼的时候按频率排序。
 *
 *   STATIC   代码里写死，发版才变         身份、工具策略
 *   SESSION  进会话时算一次，中途可能改     工作目录、技能目录、记忆目录
 *   ROUND    每轮都可能变                 当前待办看板
 *
 * 排序不是审美问题：模型服务的前缀缓存按 token 前缀命中，第一处不同的字符之后全部作废。
 * 把一段每轮都变的东西排到前面，等于让后面所有内容每轮都重付一次钱。
 *
 * 另一件事更重要：**每轮重拼不等于缓存失效**。重拼是一段字符串拼接的开销，
 * 缓不缓存看的是拼出来的字节。所以这里每轮都拼，然后比字节——字节没变就什么都不用做。
 */
public final class SystemPrompt {

    /** 变化频率。排序就按它，这是本篇唯一一条硬规矩 */
    public enum Kind { STATIC, SESSION, ROUND }

    /**
     * 一段。name 用来在日志和 diff 里指认是哪段变了，rank 决定同一频段内的先后。
     * rank 的用法：同是 SESSION，技能目录比记忆目录先——技能进会话就不变，记忆会被写。
     */
    public record Section(String name, Kind kind, int rank, String text) {
        int band() {
            return kind.ordinal() * 1000 + rank;
        }
    }

    /** 拼好的结果。prefix 是「第一段 ROUND 之前」的部分，也就是缓存能命中的上限 */
    public record Assembled(String text, String prefix, String prefixHash, boolean prefixReused,
                            List<Section> kept, List<String> dropped, List<String> changed, int tokens) {
    }

    /** system prompt 的预算（估算 token）。超了要裁，裁谁见 DROPPABLE */
    static final int DEFAULT_BUDGET = 1200;

    /**
     * 能裁的段，按先后顺序。只有一段。
     *
     * 判断依据只有一条：这段信息模型自己能不能拿到，而且拿到的还是最新的。
     * 工作目录符合——pwd 一条命令，随时是最新的。
     * 待办看板看着也符合（tool 结果里有一份），但那一份是「上一次写入时的快照」，
     * 中途状态变了它就是旧的；砍掉它等于让模型按过期计划干活，所以不砍。
     * 技能目录和记忆目录砍了就真不知道了：一个是「有这个能力」，一个是「上次踩过的坑」。
     */
    private static final List<String> DROPPABLE = List.of("workspace");

    /**
     * 反例开关。naive = 不排序，按「直觉顺序」拼：先把当前时间告诉模型（它得知道现在几点），
     * 再按重要性依次排。看着很有道理，实测每轮 system 都不一样。
     */
    private static final boolean NAIVE = "naive".equals(System.getenv().getOrDefault("PROMPT_MODE", "sorted"));

    private final int budget = Integer.parseInt(
            System.getenv().getOrDefault("PROMPT_BUDGET", String.valueOf(DEFAULT_BUDGET)));

    private Map<String, String> lastSections = Map.of();
    private String lastPrefix;
    private int reused;
    private int rebuilt;
    /** 被裁过的段名。同一个段每轮都会被裁一次，按「段」计数才是那个数 */
    private final Set<String> trimmed = new LinkedHashSet<>();
    private boolean overBudgetReported;

    public static boolean naive() {
        return NAIVE;
    }

    /**
     * 排序 → 裁剪 → 拼接。每轮都调。
     *
     * 之所以敢每轮调：一次拼接 + 两次 SHA-1 是微秒级，和一轮模型调用比可以忽略。
     * 真正贵的是「拼出来的字节变了」，那件事不在这里发生——它取决于各段的内容有没有变。
     */
    public Assembled assemble(List<Section> sections) {
        List<Section> ordered = new ArrayList<>();
        for (Section s : sections) {
            if (s.text() != null && !s.text().isBlank()) {
                ordered.add(s);
            }
        }
        if (!NAIVE) {
            ordered.sort(Comparator.comparingInt(Section::band));   // 稳定排序：同 band 保持调用方顺序
        }

        List<Section> kept = new ArrayList<>(ordered);
        List<String> dropped = new ArrayList<>();
        int tokens = tokens(join(kept));
        for (String name : DROPPABLE) {
            if (tokens <= budget) {
                break;
            }
            int at = indexOf(kept, name);
            if (at < 0) {
                continue;
            }
            kept.remove(at);
            dropped.add(name);
            trimmed.add(name);
            tokens = tokens(join(kept));
        }

        // 砍完还超：报一次，然后照发。为了压到预算以下去砍记忆目录，是拿「下次别再犯」
        // 换「这次省几十个 token」——不划算，而且这种交换一旦写进代码就没人记得为什么
        if (tokens > budget && !overBudgetReported) {
            overBudgetReported = true;
            System.out.println("   [prompt] ⚠ " + tokens + " token 超预算 " + budget
                    + "：能裁的已经裁完，记忆目录和技能目录不裁，照发");
        }

        String text = join(kept);
        String prefix = prefixOf(kept);
        String prefixHash = hash(prefix);
        boolean prefixReused = prefixHash.equals(lastPrefix);
        if (lastPrefix == null) {
            rebuilt++;
        } else if (prefixReused) {
            reused++;
        } else {
            rebuilt++;
        }
        lastPrefix = prefixHash;

        List<String> changed = diff(lastSections, kept);
        lastSections = hashes(kept);

        return new Assembled(text, prefix, prefixHash, prefixReused, kept, dropped, changed, tokens);
    }

    /** 段表：拼一次之后打一遍，让人看见「谁在前面、谁被裁了」 */
    public void plan(Assembled a) {
        System.out.println("   [prompt] " + a.kept().size() + " 段 → " + a.tokens() + " token（预算 "
                + budget + "）");
        for (Section s : a.kept()) {
            System.out.printf("            %-7s %-11s %4d token%n", s.kind(), s.name(), tokens(s.text()));
        }
        if (!a.dropped().isEmpty()) {
            System.out.println("            ⚠ 超预算，裁掉：" + String.join(", ", a.dropped())
                    + "（模型自己查得到的段，砍了不影响判断）");
        }
    }

    /** 收尾：前缀复用了多少次。这个数就是「排序有没有白排」 */
    public void report() {
        System.out.println("\n   [prompt] 本会话 " + (reused + rebuilt) + " 轮：前缀复用 " + reused
                + " 次，重算 " + rebuilt + " 次；裁掉的段 " + (trimmed.isEmpty() ? "无" : String.join(",", trimmed)));
    }

    // ---------- 内部 ----------

    private static int indexOf(List<Section> sections, String name) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String join(List<Section> sections) {
        StringBuilder sb = new StringBuilder();
        for (Section s : sections) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(s.text().strip());
        }
        return sb.toString();
    }

    /** 第一段 ROUND 之前的部分。naive 模式下 ROUND 可能夹在中间，所以按位置找，不按数量算 */
    private static String prefixOf(List<Section> sections) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).kind() == Kind.ROUND) {
                return join(sections.subList(0, i));
            }
        }
        return join(sections);
    }

    private static Map<String, String> hashes(List<Section> sections) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Section s : sections) {
            out.put(s.name(), hash(s.text().strip()));
        }
        return out;
    }

    /** 哪几段的字节跟上一轮不一样。前缀为什么变，答案在这 */
    private static List<String> diff(Map<String, String> before, List<Section> now) {
        List<String> changed = new ArrayList<>();
        for (Section s : now) {
            String h = hash(s.text().strip());
            if (!h.equals(before.get(s.name()))) {
                changed.add(s.name());
            }
        }
        return changed;
    }

    private static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }

    private static int tokens(String text) {
        return Compactor.tokens(text);
    }
}
