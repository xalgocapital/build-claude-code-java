package dev.lcc.s15;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Supplier;

/**
 * s08 的全部新东西：上下文压缩。
 *
 * 前面的机制都在「少放点东西进 messages」：s06 让子 Agent 只回结论，s07 让技能正文按需加载。
 * 但已经进去的东西不会自己出去——读过的文件、跑过的命令，全留在数组里。长任务照样会满。
 *
 * 所以这里要做的是唯一一件前面没做过的事：**把 messages 删掉一段，用摘要顶替**。
 *
 * 删一段，风险全在边界上：
 *   1. 切点不能落在 assistant(tool_calls) 和它的 tool 结果之间 —— 否则 tool_call_id 悬空，API 直接 400
 *   2. 原始目标不能删 —— 摘要会漂，目标得留在原位
 *   3. 最近几轮不能删 —— 模型正在用它们
 *   4. 「还在生效的状态」不能只靠摘要 —— 待办清单、已加载的技能名，压缩后要重新贴回去
 *
 * 第 1 条是硬约束，第 4 条是经验——写压缩最容易漏的就是第 4 条。
 */
public final class Compactor {
    /** 上下文预算，按估算 token 算。真实项目按模型窗口设，这里给个能跑起来的值 */
    static final int DEFAULT_LIMIT = 8000;
    /** 尾巴至少留几条消息（两轮工具调用）：模型正靠它们干活，删了就等于失忆 */
    static final int KEEP_RECENT = 4;
    /** 一次至少压掉这么多条。压一次要调一次模型，压得太少不如不压——这条防的是抖动 */
    static final int MIN_DROP = 6;
    /** 到预算的这个比例就开始压，留出余量给下一轮的回复 */
    static final double TRIGGER_RATIO = 0.75;

    /**
     * 切点开关：safe = 走边界判断，naive = 直接把「尾巴之前的一切」换掉。
     *
     * naive 是留给大家复现失败的——它一定会把 assistant(tool_calls) 和它的 tool 结果切开，
     * 下一次请求就带着孤立的 tool_call_id 出去。真实 API 会直接 400，本地假端点会把违规条数打出来。
     */
    private static final boolean NAIVE = "naive".equals(System.getenv().getOrDefault("CUT_MODE", "safe"));

    /** 写进摘要请求时，单条工具结果最多带这么多字符——摘要请求本身也不能无限大 */
    private static final int TRANSCRIPT_CHARS = 600;
    private static final int ARGS_CHARS = 200;

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    /** 压缩后要重新贴回去的「活状态」：待办清单、已加载的技能名 */
    private final Supplier<String> liveState;
    private final int limit;

    private int compactions;
    private int tokensSaved;
    /** 被服务端拒绝过一次时置真：临时跳过「超没超」的判断 */
    private boolean forced;

    public Compactor(OpenAICompatClient client, Supplier<String> liveState) {
        this.client = client;
        this.mapper = client.mapper();
        this.liveState = liveState;
        this.limit = Integer.parseInt(System.getenv().getOrDefault("CONTEXT_LIMIT", String.valueOf(DEFAULT_LIMIT)));
    }

    /**
     * 每轮调模型之前调一次。没超阈值就原样返回，超了就地把 messages 改小。
     *
     * 注意是「就地改」：AgentLoop 手里握着同一个 ArrayNode，改完下一次请求自然就小了。
     */
    /**
     * 服务端说上下文超长时，绕过阈值直接压一次。
     *
     * s08 的 maybeCompact 是「自己估算，超了才压」。估算和真实 token 数总会有偏差，
     * 偏差大到被服务端拒了，就必须承认估算错了：这时候再拿估算值判断要不要压，就是自欺欺人。
     */
    public boolean forceCompact(ArrayNode messages) {
        forced = true;
        try {
            return maybeCompact(messages);
        } finally {
            forced = false;
        }
    }

    public boolean maybeCompact(ArrayNode messages) {
        int before = estimate(messages);
        int budget = (int) (limit * TRIGGER_RATIO);
        if (!forced && before < budget) {
            return false;
        }

        int head = 2;   // [0] system、[1] 原始目标，永远不动

        // 能压多少，取决于「总共 - 尾巴 - 开头」。太少就等下一轮，别为了压而压
        int available = messages.size() - KEEP_RECENT - head;
        if (available < MIN_DROP) {
            System.out.println("   [compact] 上下文 " + before + " token 已超预算，但能压的只有 "
                    + Math.max(available, 0) + " 条（< " + MIN_DROP + "），这轮先不压");
            return false;
        }

        int cut = NAIVE ? messages.size() - KEEP_RECENT : safeCut(messages, head + 2, KEEP_RECENT);
        if (NAIVE) {
            System.out.println("   [compact] ⚠ CUT_MODE=naive：不做边界判断，尾巴之前的一律换掉");
        }
        if (cut < 0) {
            System.out.println("   [compact] 上下文 " + before + " token 已超预算，但找不到安全切点，这轮先不压");
            return false;
        }

        String digest = summarize(messages, head, cut);
        if (digest == null || digest.isBlank()) {
            // 摘要失败就不压：宁可超预算，也不能把消息删了却什么都没留下
            System.out.println("   [compact] 摘要失败，这轮不压（原文必须先保住）");
            return false;
        }

        int dropped = cut - head;
        ArrayNode rebuilt = mapper.createArrayNode();
        for (int i = 0; i < head; i++) {
            rebuilt.add(messages.get(i));
        }
        rebuilt.add(summaryMessage(digest, dropped, before));
        for (int i = cut; i < messages.size(); i++) {
            rebuilt.add(messages.get(i));
        }

        messages.removeAll();
        messages.addAll(rebuilt);

        int after = estimate(messages);
        compactions++;
        tokensSaved += before - after;
        System.out.println("\n   [compact] 第 " + compactions + " 次压缩：" + dropped + " 条 → 1 条摘要"
                + "，上下文 " + before + " → " + after + " token（估算）"
                + "，摘要 " + digest.length() + " 字符");
        return true;
    }

    /** 收尾统计，AgentLoop 退出前打印 */
    public void report() {
        if (compactions == 0) {
            System.out.println("\n   [compact] 本会话没触发压缩（上下文一直没到预算）");
            return;
        }
        System.out.println("\n   [compact] 本会话压缩 " + compactions + " 次，累计省下 "
                + tokensSaved + " token（估算）");
    }

    /**
     * 找切点。返回 tail 的起始下标，切不动返回 -1。
     *
     * 从后往前找（切点越靠后，压掉的越多、尾巴越短）：
     *   1. 优先切在「回合结束」——assistant 消息、且不带 tool_calls
     *   2. 一个超长回合里没有这种边界（全是一串工具调用），退到工具组边界：切在非 tool 消息上
     *
     * 第 2 步是硬约束：tail 只要以 tool 消息开头，它对应的 assistant(tool_calls) 就被删了，
     * tool_call_id 悬空，下一次请求直接被拒。
     */
    static int safeCut(ArrayNode messages, int from, int keep) {
        int latest = messages.size() - keep;    // 尾巴起点最晚到这儿，再往后就没得删了
        int start = Math.max(from, 1);

        for (int i = latest; i >= start; i--) {
            JsonNode m = messages.get(i);
            if ("assistant".equals(m.path("role").asText()) && !m.path("tool_calls").isArray()) {
                return i;
            }
        }
        for (int i = latest; i >= start; i--) {
            if (!"tool".equals(messages.get(i).path("role").asText())) {
                return i;
            }
        }
        return -1;
    }

    /** 让模型把要丢掉的那段压成一段摘要。这一问本身也要花 token，所以不划算时别乱压 */
    private String summarize(ArrayNode messages, int from, int to) {
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        // 目标单独喂给它，否则摘要会写成「一堆操作流水账」，与当前任务脱钩
        system.put("content", SUMMARIZER + "\n\n原始目标：" + messages.get(1).path("content").asText(""));

        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", transcript(messages, from, to));

        ArrayNode request = mapper.createArrayNode();
        request.add(system);
        request.add(user);

        JsonNode response = client.createChatCompletion(request, mapper.createArrayNode());
        return response.path("choices").path(0).path("message").path("content").asText("").strip();
    }

    /** 把一段 messages 摊平成纯文本。工具结果要截断，不然摘要请求和原文一样大 */
    private String transcript(ArrayNode messages, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            JsonNode m = messages.get(i);
            String role = m.path("role").asText();
            JsonNode calls = m.path("tool_calls");
            if ("assistant".equals(role) && calls.isArray() && !calls.isEmpty()) {
                for (JsonNode call : calls) {
                    sb.append("助手 → 调用 ").append(call.path("function").path("name").asText())
                            .append('(')
                            .append(clip(call.path("function").path("arguments").asText(""), ARGS_CHARS))
                            .append(")\n");
                }
            } else if ("tool".equals(role)) {
                sb.append("工具结果: ").append(clip(textOf(m), TRANSCRIPT_CHARS)).append('\n');
            } else if ("assistant".equals(role)) {
                sb.append("助手: ").append(clip(textOf(m), TRANSCRIPT_CHARS)).append('\n');
            } else {
                sb.append("用户: ").append(clip(textOf(m), TRANSCRIPT_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 顶替原文的那条消息。两块内容：摘要 + 活状态。
     *
     * 活状态为什么不能指望摘要？摘要是一次性的快照，而待办清单是每轮都在变的东西。
     * 压缩发生在某一轮，清单可能在那之后又更新了——重新贴一次最省事，也最不容易错。
     */
    private ObjectNode summaryMessage(String digest, int dropped, int before) {
        StringBuilder sb = new StringBuilder();
        sb.append("<summary>\n");
        sb.append("（前 ").append(dropped).append(" 条消息已压缩移除，原上下文约 ")
                .append(before).append(" token）\n");
        sb.append(digest).append('\n');
        sb.append("</summary>");

        String state = liveState == null ? null : liveState.get();
        if (state != null && !state.isBlank()) {
            sb.append("\n\n<live-state>\n").append(state.strip()).append("\n</live-state>");
        }
        sb.append("\n\n继续当前任务。摘要里写「已完成」的不要重做；需要细节就重新读文件或重新取技能。");

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", sb.toString());
        return message;
    }

    // ---------- 体积估算 ----------

    /**
     * 估 token：一个汉字算一个，一个英文单词算一个。
     *
     * 粗，但够用——压缩只需要判断「是不是快满了」，不需要精确到个位。
     * 真要按照 tokenizer 算，就得把模型的分词表拖进来，对一篇讲机制的代码不值得。
     */
    static int tokens(String text) {
        int cjk = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    words++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return cjk + words;
    }

    static int estimate(ArrayNode messages) {
        int total = 0;
        for (JsonNode m : messages) {
            total += tokens(textOf(m));
            for (JsonNode call : m.path("tool_calls")) {
                total += tokens(call.path("function").path("name").asText(""))
                        + tokens(call.path("function").path("arguments").asText(""));
            }
        }
        return total;
    }

    private static String textOf(JsonNode message) {
        JsonNode content = message.path("content");
        return content.isTextual() ? content.asText() : "";
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + " …(截断)";
    }

    private static final String SUMMARIZER = """
            你在压缩一段 Agent 会话的历史记录。把下面的记录压成不超过 400 字的中文摘要，
            只保留后面还用得上的东西：
            1) 已经确认的事实和结论（在哪个文件、哪一行、什么值）
            2) 改过的文件路径，以及改了什么
            3) 试过但失败的做法，避免后面重复走一遍
            4) 还没做完的下一步
            不要客套话，不要复述工具调用的细节，不要写记录里没有的内容。
            """;
}
