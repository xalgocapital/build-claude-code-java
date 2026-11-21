package com.xalgocapital.claude.s07;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/**
 * s07: 主 Agent 循环。
 *
 * 相对 s06 有两处新增，都不是循环主干：
 *   1. system prompt 末尾拼一段技能目录（名字 + 一句话），正文不进来
 *   2. 分发表里多第 8 个工具 skill，调用时才把正文当 tool 结果回灌
 *
 * 循环仍是 while(true) 那一套：调模型→扫 tool_calls→dispatch→回灌。
 * 技能不是新分支，它还是又一个 handler——只不过这个 handler 返回的是
 * 「接下来该怎么做」的说明书，而不是命令输出。
 */
public class AgentLoop {
    private static final int NAG_AFTER = 3;   // s05 的提醒节奏，本篇不动

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private final HookRegistry hooks;
    private final TodoStore todos;
    private final SkillRegistry skills;
    private final Subagent subagent;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个基础工具

        this.todos = new TodoStore(mapper);
        registry.register("todo_write", TodoStore.DESCRIPTION, todos.schema(), todos::update);

        // s07 第一处：启动时只把「目录」读进内存，正文还躺在磁盘上
        this.skills = new SkillRegistry(mapper,
                SkillLoader.loadAll(Path.of(System.getenv().getOrDefault("SKILL_DIR", "skills"))));
        System.out.println("   [skill] 技能目录 " + skills.size() + " 项：" + skills.names());

        this.hooks = new HookRegistry();
        hooks.register("UserPromptSubmit", BuiltinHooks.contextInjectHook());
        hooks.register("PreToolUse", BuiltinHooks.permissionHook(skills));   // 权限 hook 要知道哪些工具被预授权
        hooks.register("PreToolUse", BuiltinHooks.logHook());
        hooks.register("PostToolUse", BuiltinHooks.largeOutputHook());
        hooks.register("Stop", BuiltinHooks.summaryHook());

        // s06：第 7 个工具。handler 一执行，就开一个独立上下文的子 Agent。
        this.subagent = new Subagent(client, hooks);
        registry.register("task",
                "Launch a subagent to handle a self-contained subtask. "
                        + "The subagent has its own fresh context and returns only its final conclusion. "
                        + "Use it for exploration or multi-step work whose intermediate steps you do not need.",
                subagent.schema(),
                args -> subagent.run(args.path("description").asText("")));

        // s07 第二处：第 8 个工具。handler 返回的是「说明书正文」，不是命令输出。
        registry.register("skill", SkillRegistry.DESCRIPTION, skills.schema(),
                args -> skills.load(args.path("name").asText("")));
    }

    public void run(String query) {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SYSTEM + skills.catalogSection());
        messages.add(system);

        hooks.trigger(Event.userPrompt(query));

        ObjectNode first = mapper.createObjectNode();
        first.put("role", "user");
        first.put("content", query);
        messages.add(first);

        ArrayNode tools = registry.schemas();
        int toolCount = 0;
        int roundsSinceTodo = 0;

        while (true) {
            if (roundsSinceTodo >= NAG_AFTER) {
                ObjectNode reminder = mapper.createObjectNode();
                reminder.put("role", "user");
                reminder.put("content",
                        "<reminder>Update your todos: mark finished items completed and pick the next pending one.</reminder>");
                messages.add(reminder);
                System.out.println("\n   [NAG] 连续 " + roundsSinceTodo + " 轮没更新 todo，注入提醒");
                roundsSinceTodo = 0;
            }

            JsonNode response = client.createChatCompletion(messages, tools);
            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");
            messages.add(message);

            JsonNode toolCalls = message.path("tool_calls");
            boolean hasTools = toolCalls.isArray() && !toolCalls.isEmpty();

            if (hasTools && usedTodo(toolCalls)) {
                roundsSinceTodo = 0;
            } else if (hasTools) {
                roundsSinceTodo++;
            }

            if (!hasTools) {
                String force = hooks.trigger(Event.stop(toolCount));
                if (force != null) {
                    ObjectNode injection = mapper.createObjectNode();
                    injection.put("role", "user");
                    injection.put("content", force);
                    messages.add(injection);
                    continue;
                }
                printText(message);
                return;
            }

            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                JsonNode args = parseArgs(call.path("function").path("arguments"));

                System.out.println("\n→ " + name + "(" + brief(name, args) + ")");

                String blocked = hooks.trigger(Event.preTool(name, args));
                if (blocked != null) {
                    ObjectNode denied = mapper.createObjectNode();
                    denied.put("role", "tool");
                    denied.put("tool_call_id", id);
                    denied.put("content", blocked);
                    messages.add(denied);
                    continue;
                }

                String output = registry.dispatch(name, args);
                toolCount++;
                hooks.trigger(Event.postTool(name, args, output));

                System.out.println(output);
                ObjectNode toolResult = mapper.createObjectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", id);
                toolResult.put("content", output);
                messages.add(toolResult);
            }
        }
    }

    private boolean usedTodo(JsonNode toolCalls) {
        for (JsonNode call : toolCalls) {
            if ("todo_write".equals(call.path("function").path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 打印参数时压成一行摘要：todo_write 列条数，task 只显示任务描述 */
    private String brief(String name, JsonNode args) {
        if ("todo_write".equals(name)) {
            JsonNode arr = args.path("todos");
            return arr.isArray() ? "[" + arr.size() + " tasks]" : args.toString();
        }
        if ("task".equals(name)) {
            return "\"" + args.path("description").asText("") + "\"";
        }
        if ("skill".equals(name)) {
            return "\"" + args.path("name").asText("") + "\"";
        }
        return args.toString();
    }

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

    private void printText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            System.out.println("\n" + content.asText());
        }
    }

    public static void main(String[] args) {
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.deepseek.com/v1");
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("MODEL_ID", "deepseek-chat");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请先设置环境变量 OPENAI_API_KEY");
            System.exit(1);
        }
        String query = args.length > 0
                ? String.join(" ", args)
                : "这个版本修了 session 过期和列表分页，帮我写一份发布说明，然后打 v1.0.0 的 tag。";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    private static final String SYSTEM = """
            You are a helpful coding agent. Use the provided tools to inspect and modify files.
            For any multi-step task, call todo_write FIRST to lay out a plan, then mark each item
            in_progress / completed as you make progress. Keep the plan up to date.
            When a subtask would need a lot of exploration whose intermediate steps you do not care
            about, hand it to the task tool: the subagent has its own fresh context and returns only
            a short conclusion.
            The skills listed at the end of this prompt are NOT loaded. When the work matches one of
            them, call skill(name) first and then follow what it returns.
            Prefer read_file / write_file / edit_file / glob over shell commands when possible.
            When the task is done, answer in plain text without calling a tool.
            """;
}
