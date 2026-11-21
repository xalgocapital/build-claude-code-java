package com.xalgocapital.claude.s15;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个有名字的轻量会话。
 *
 * 和 s06 的 Subagent 比，三处不同：
 *   1) 它不死。有 step()：走一步、停下、等下一次 tick，messages 留着
 *   2) 它有自己的收件箱。两次 step 之间 lead 说的话躺在盘上，下一次 step 开头才读进来
 *   3) 它自己的 system 里有任务板，认领哪条由它定，不是 lead 点名
 *
 * 共享的只有 OpenAICompatClient 和 TaskStore。工具表是各自一份：
 * 成员只该有读的工具。三个人同时改一个文件，谁也说不清是谁改坏的。
 */
public final class Teammate {
    public enum State { ACTIVE, DONE, FAILED }

    /** 一个成员最多走这么多步。轮转调度下没人抢它的 CPU，但也没人替它踩刹车 */
    private static final int MAX_STEPS = 12;

    private final String name;
    private final String role;
    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final HookRegistry hooks;
    private final Mailbox mailbox;
    private final SystemPrompt prompt = new SystemPrompt();
    private final ToolRegistry registry;
    private final TaskStore tasks;

    private final ArrayNode messages;
    private String lastSystem;
    private State state = State.ACTIVE;
    private int steps;
    private String conclusion = "";

    Teammate(String name, String role, String initialPrompt,
             OpenAICompatClient client, HookRegistry hooks, Mailbox mailbox, TaskStore tasks) {
        this.name = name;
        this.role = role;
        this.client = client;
        this.mapper = client.mapper();
        this.hooks = hooks;
        this.mailbox = mailbox;
        this.tasks = tasks;

        // 两个读工具 + 认领。没有 bash / write_file，也没有 team_spawn——能干活，但不能再拉人
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.installReadOnly(registry);
        registerClaim();

        this.messages = mapper.createArrayNode();
        this.lastSystem = buildSystem();
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", lastSystem);
        messages.add(system);
        messages.add(userMessage(initialPrompt));
    }

    public String name() {
        return name;
    }

    public String role() {
        return role;
    }

    public State state() {
        return state;
    }

    public int steps() {
        return steps;
    }

    /**
     * 往前走一步：收信 → 一次模型请求 → 执行它要的工具。
     *
     * 返回要交给 lead 的一句话；还在做就返回 null。这个返回值会被投进 lead 的收件箱，
     * 下一轮作为一条 user 消息出现——lead 才知道成员干完了。
     */
    public String step() {
        if (state != State.ACTIVE) {
            return null;
        }

        // 这一 tick 新到的消息进上下文，和它自己的历史并排放着
        for (Mailbox.Msg m : mailbox.drain(name)) {
            messages.add(userMessage(m.render()));
        }

        // 任务板可能被别人改了。重拼 system，只比字节（s10 那一套原样用在这里）
        String system = buildSystem();
        if (!system.equals(lastSystem)) {
            ((ObjectNode) messages.get(0)).put("content", system);
            lastSystem = system;
        }

        steps++;
        JsonNode response;
        try {
            response = client.createChatCompletion(messages, registry.schemas());
        } catch (ApiException e) {
            // 一个成员挂了不该带走团队。降级成 FAILED，把原因投给 lead
            state = State.FAILED;
            System.out.println("   [team:" + name + "] 这一步失败了：" + e.failure() + " HTTP " + e.status());
            return "（我这一步失败了：" + e.failure() + " HTTP " + e.status() + "）";
        }

        JsonNode message = response.path("choices").path(0).path("message");
        messages.add(message);

        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            // 没有工具调用 = 它认为干完了。和 s06 不一样：不 return，只挂状态
            state = State.DONE;
            conclusion = message.path("content").asText("");
            System.out.println("   [team:" + name + "] 第 " + steps + " 步收工");
            return conclusion.isBlank() ? "（" + name + " 没有输出结论）" : conclusion;
        }

        for (JsonNode call : toolCalls) {
            String id = call.path("id").asText();
            String tool = call.path("function").path("name").asText();
            JsonNode args = parseArgs(call.path("function").path("arguments"));

            System.out.println("   [team:" + name + "] → " + tool + "(" + brief(tool, args) + ")");

            // 门禁不跳过。换个脑子干活，门还是同一道门
            String blocked = hooks.trigger(Event.preTool(tool, args));
            if (blocked != null) {
                messages.add(toolMessage(id, blocked));
                continue;
            }

            String output;
            try {
                output = registry.dispatch(tool, args);
            } catch (RuntimeException e) {
                output = "(error: " + e.getMessage() + ")";
            }
            hooks.trigger(Event.postTool(tool, args, output));
            System.out.println("   [team:" + name + "]   " + oneLine(output));
            messages.add(toolMessage(id, output));
        }

        if (steps >= MAX_STEPS) {
            state = State.FAILED;
            System.out.println("   [team:" + name + "] 走了 " + MAX_STEPS + " 步还没结论，收回");
            return "（" + name + " 走了 " + MAX_STEPS + " 步还没给结论，我把它收回了）";
        }
        return null;
    }

    /**
     * 成员自己的 system。复用 s10 的分段拼装：身份 → 工具策略 → 任务板。
     *
     * 身份段里写名字和角色，这是整个实验唯一能区分成员的地方——
     * 模型那边看到的是同一份权重，唯一的不同就是这几行字。
     */
    private String buildSystem() {
        List<SystemPrompt.Section> sections = new ArrayList<>();
        sections.add(new SystemPrompt.Section("identity", SystemPrompt.Kind.STATIC, 10,
                "You are a teammate named " + name + " on a small team working inside a repository.\n"
                        + "Your role: " + role + "\n"
                        + "You cannot delegate: you have no way to spawn another teammate.\n"
                        + "You can read files and claim tasks. You cannot write files — if the work "
                        + "needs an edit, report it to the lead instead of doing it."));
        sections.add(new SystemPrompt.Section("tool-policy", SystemPrompt.Kind.STATIC, 20,
                "Read before you claim: use read_file / glob to get the fact your role asks for.\n"
                        + "Claim with task_update(id, status=IN_PROGRESS, owner=" + name + "). "
                        + "If the task is already owned by someone else the tool rejects it — do not "
                        + "retry the same claim, pick another task or report back.\n"
                        + "When your task is finished, mark it COMPLETED with task_update.\n"
                        + "Then reply in plain text without calling a tool. That text is your "
                        + "conclusion, and it is the ONLY thing the lead will see.\n"
                        + "Keep it under 120 words and lead with the fact, not the process."));
        sections.add(new SystemPrompt.Section("tasks", SystemPrompt.Kind.ROUND, 90, taskSection()));
        return prompt.assemble(sections).text();
    }

    private String taskSection() {
        String board = tasks.board();
        return board.isBlank() ? "" : "Tasks on disk (shared with everyone):\n" + board;
    }

    /**
     * 成员版的 task_update：只有 id / status / owner，没有 blockedBy。
     *
     * 和 lead 那一份是有意分开的。成员不该重排依赖——它看不到全局，
     * 改一条 blockedBy 可能让另一条永远开不了工，而且没人知道是谁改的。
     */
    private void registerClaim() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("id", prop("string", "Task id, e.g. T1"));
        props.set("status", prop("string", "PENDING / IN_PROGRESS / COMPLETED"));
        props.set("owner", prop("string", "Your name. Set it when you claim the task"));
        schema.set("properties", props);
        var req = mapper.createArrayNode();
        req.add("id");
        req.add("status");
        schema.set("required", req);

        registry.register("task_update",
                "Change a task's status. Claiming a task that someone else already owns is rejected.",
                schema,
                args -> {
                    try {
                        TaskStore.Task t = tasks.update(args.path("id").asText(""),
                                TaskStore.Status.valueOf(args.path("status").asText("PENDING").toUpperCase()),
                                args.path("owner").asText(""));
                        return "Updated " + t.id() + " → " + t.status()
                                + (t.owner().isBlank() ? "" : "（" + t.owner() + "）");
                    } catch (RuntimeException e) {
                        // 冲突不是异常，是这一轮最该被看见的信息。原样回给模型
                        return "(error: " + e.getMessage() + ")";
                    }
                });
    }

    private ObjectNode prop(String type, String desc) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", type);
        p.put("description", desc);
        return p;
    }

    private ObjectNode userMessage(String text) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", "user");
        n.put("content", text);
        return n;
    }

    private ObjectNode toolMessage(String id, String text) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", "tool");
        n.put("tool_call_id", id);
        n.put("content", text);
        return n;
    }

    private static String brief(String tool, JsonNode args) {
        if ("read_file".equals(tool)) {
            return "\"" + args.path("path").asText("") + "\"";
        }
        if ("glob".equals(tool)) {
            return "\"" + args.path("pattern").asText("") + "\"";
        }
        if ("task_update".equals(tool)) {
            return args.path("id").asText("") + " → " + args.path("status").asText("");
        }
        return args.toString();
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
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
}
