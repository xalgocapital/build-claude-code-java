package com.xalgocapital.claude.s13;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s06 的全部新东西都在这个文件里：一个可以被主 Agent 调用的「子 Agent」。
 *
 * 它和主循环长得几乎一样——同样调模型、扫 tool_calls、dispatch、回灌结果。
 * 差别只有三点，而这三点就是「子代理」这个概念的全部：
 *
 *   1) 独立上下文：子 Agent 从一个全新的 messages[] 开始，父对话的历史一条都不带
 *   2) 只回传结论：跑完把最后那条文本拿出来交回去，中间几十轮全部丢掉
 *   3) 禁止递归：子 Agent 的工具表里没有 task，它没法再 spawn 下一层
 *
 * 还有一条容易被忽略：子 Agent 的工具调用照样过 PreToolUse hook。
 * 上下文隔离不等于权限隔离——换个脑子干活，门禁还是同一道门。
 */
public class Subagent {
    /** 安全上限：子 Agent 最多跑这么多轮，防止它在一个子任务里死循环 */
    private static final int MAX_ROUNDS = 30;

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final HookRegistry hooks;

    /** 子 Agent 自己的工具表：5 个基础工具，没有 todo_write，也没有 task */
    private final ToolRegistry subRegistry;

    public Subagent(OpenAICompatClient client, HookRegistry hooks) {
        this.client = client;
        this.mapper = client.mapper();
        this.hooks = hooks;
        this.subRegistry = new ToolRegistry(mapper);
        BuiltinTools.install(subRegistry);
    }

    /** task 工具的参数 schema，主 Agent 注册时用 */
    public ObjectNode schema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode desc = mapper.createObjectNode();
        desc.put("type", "string");
        desc.put("description",
                "A self-contained description of the subtask. State the goal and what to report back.");

        ObjectNode props = mapper.createObjectNode();
        props.set("description", desc);
        schema.set("properties", props);

        ArrayNode required = mapper.createArrayNode();
        required.add("description");
        schema.set("required", required);
        return schema;
    }

    /**
     * 派一个子 Agent 去做 description 描述的事，只返回它最后的结论文本。
     */
    public String run(String description) {
        System.out.println("\n   [Subagent spawned] " + oneLine(description));

        // 1) 全新上下文。只有 system 和这一条任务描述，父对话的 120 条消息一条没进来。
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SUB_SYSTEM);
        messages.add(system);

        ObjectNode task = mapper.createObjectNode();
        task.put("role", "user");
        task.put("content", description);
        messages.add(task);

        ArrayNode tools = subRegistry.schemas();

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode message = response.path("choices").path(0).path("message");
            messages.add(message);

            JsonNode toolCalls = message.path("tool_calls");
            boolean hasTools = toolCalls.isArray() && !toolCalls.isEmpty();

            // 没有工具调用 = 它认为干完了。只把这条文本拿走，messages 整个扔掉。
            if (!hasTools) {
                String conclusion = message.path("content").asText("");
                System.out.println("   [Subagent done] 跑了 " + round + " 轮，回传 "
                        + conclusion.length() + " chars 结论");
                return conclusion.isBlank() ? "(subagent returned no text)" : conclusion;
            }

            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                JsonNode args = parseArgs(call.path("function").path("arguments"));

                System.out.println("   [sub] → " + name + "(" + args + ")");

                // 2) 门禁不跳过：子 Agent 的每次工具调用照样过主 Agent 那套 hook。
                String blocked = hooks.trigger(Event.preTool(name, args));
                if (blocked != null) {
                    ObjectNode denied = mapper.createObjectNode();
                    denied.put("role", "tool");
                    denied.put("tool_call_id", id);
                    denied.put("content", blocked);
                    messages.add(denied);
                    continue;
                }

                String output = subRegistry.dispatch(name, args);
                hooks.trigger(Event.postTool(name, args, output));

                ObjectNode result = mapper.createObjectNode();
                result.put("role", "tool");
                result.put("tool_call_id", id);
                result.put("content", output);
                messages.add(result);
            }
        }

        System.out.println("   [Subagent done] 触达 " + MAX_ROUNDS + " 轮上限，强制收回");
        return "(subagent hit the " + MAX_ROUNDS + "-round limit without producing a conclusion)";
    }

    private String oneLine(String text) {
        String s = text.replaceAll("\\s+", " ").strip();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** arguments 兼容两种形态：JSON 字符串 或 已解析对象（同前几篇） */
    private JsonNode parseArgs(JsonNode node) {
        if (node.isTextual()) {
            try {
                return mapper.readTree(node.asText());
            } catch (Exception e) {
                return mapper.createObjectNode();
            }
        }
        return node.isObject() ? node : mapper.createObjectNode();
    }

    private static final String SUB_SYSTEM = """
            You are a focused subagent working on one subtask.
            Use the provided tools to inspect and modify files, then stop.
            You cannot delegate: you have no task tool.
            When you are done, reply with a SHORT conclusion — what you found, or what you changed.
            Keep it under 200 words. The caller only sees that text, not your tool output.
            """;
}
