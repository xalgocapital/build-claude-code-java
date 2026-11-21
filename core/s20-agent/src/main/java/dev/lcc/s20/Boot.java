package dev.lcc.s20;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * s20 的全部新东西：显式的装配清单。
 *
 * 十九篇下来，AgentLoop 的构造函数里攒了 10 个子系统，装配顺序写死在代码先后里。
 * 能跑，但三个问题没人答得上来：
 *   谁依赖谁？             —— 答案散落在每一章的「为什么放在这」注释里
 *   谁往 system 里放了钱？ —— 只有 s10 的段表知道，装配时看不见
 *   预算不够时先砍谁？     —— s10 定了裁 workspace 一个，后面的章没再排队
 *
 * 所以把构造函数倒过来：先声明，后装配。
 * 每个子系统一行 {name, dependsOn, tokens, ready}，拓扑排序后逐个构建，
 * 启动时把整张表打出来。BOOT_MODE=naive 是反例：按字母序装配，
 * compact 会跑到 memory 前面——Compactor 在构造时抓住的 memory 引用是 null，
 * 摘要请求里从此没有记忆条目。装配顺序不是审美，是会把数据弄丢的东西。
 */
public final class Boot {

    /** 装配清单上的一行。tokens 是该子系统往 system prompt 贡献的估算，0 表示不进 system */
    public record Item(String name, List<String> dependsOn, int tokens, String detail, boolean ready) {
    }

    /** 一个待装配的子系统：声明 + 构造动作。声明顺序是拓扑排序里并列时的先后 */
    public static final class Step {
        public final String name;
        public final List<String> dependsOn;
        private final Supplier<Item> build;

        public Step(String name, List<String> dependsOn, Supplier<Item> build) {
            this.name = name;
            this.dependsOn = List.copyOf(dependsOn);
            this.build = build;
        }

        Item build() {
            return build.get();
        }
    }

    /** 装配结果：每行一条 + 声明总数。status 工具读它，断言也读它 */
    public record Result(List<Item> items, int declared) {
        public int ready() {
            return items.size();
        }

        public String names() {
            return String.join(",", items.stream().map(Item::name).toList());
        }

        public int totalTokens() {
            return items.stream().mapToInt(Item::tokens).sum();
        }
    }

    /** 反例开关。BOOT_MODE=naive = 按字母序装配，不看依赖 */
    public static boolean naive() {
        return "naive".equals(System.getenv().getOrDefault("BOOT_MODE", "boot"));
    }

    /**
     * 排序。依赖在前，并列时按声明顺序——这个顺序本身就是文档：
     * 谁排在谁前面，读表的人一眼能看出「谁等谁」。
     */
    public static List<Step> order(List<Step> steps) {
        if (naive()) {
            List<Step> sorted = new ArrayList<>(steps);
            sorted.sort(Comparator.comparing(s -> s.name));
            return sorted;
        }
        List<Step> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        List<Step> rest = new ArrayList<>(steps);
        while (!rest.isEmpty()) {
            boolean progressed = false;
            for (Step s : new ArrayList<>(rest)) {
                if (done.containsAll(s.dependsOn)) {
                    out.add(s);
                    done.add(s.name);
                    rest.remove(s);
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException("依赖成环或缺失，卡住的有：" + rest.stream().map(s -> s.name).toList());
            }
        }
        return out;
    }

    /** 排序 → 逐个构建 → 打印装配表。AgentLoop 的构造函数只剩这一行编排 */
    public static Result wire(List<Step> steps) {
        List<Step> ordered = order(steps);
        List<Item> items = new ArrayList<>();
        for (Step s : ordered) {
            items.add(s.build());
        }

        System.out.println();
        if (naive()) {
            System.out.println("[boot] BOOT_MODE=naive：按字母序装配，不看依赖（反例）");
        } else {
            System.out.println("[boot] " + items.size() + " 个子系统，按依赖顺序启动");
        }
        for (Item it : items) {
            String deps = it.dependsOn().isEmpty() ? "无" : String.join(",", it.dependsOn());
            String detail = it.tokens() > 0
                    ? it.detail() + " / " + it.tokens() + " token"
                    : it.detail();
            System.out.printf("  %-9s ←  %-17s %s%n", it.name(), deps, detail);
        }
        System.out.println("[boot] 装配表合计 " + items.stream().mapToInt(Item::tokens).sum()
                + " token（进 system 的部分），" + items.size() + "/" + steps.size() + " 就绪");
        return new Result(items, steps.size());
    }
}
