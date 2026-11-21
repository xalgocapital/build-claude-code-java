package com.xalgocapital.claude.s10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * s10: 主 Agent 循环。
 *
 * 相对 s09 动了三处，都在 system prompt 上：
 *   1. 那句 SYSTEM 常量拆成 identity / tool-policy 两段
 *   2. 技能目录、记忆目录、待办看板各自成段，按「变化频率」排序拼装
 *   3. s09 那句 if (memory.version() != memoryVersion) 删了——改成每轮都拼，只比字节
 *
 * 第 3 处是本篇的关键。s09 的写法只照顾了记忆：待办变了它不会重拼，技能少了它也不会。
 * 把判断从「什么时候拼」换成「拼出来的字节变了没有」，漏判的可能性就没了：
 * 拼一次是微秒级，判错一次是一整轮缓存白攒。
 */
public class AgentLoop {
    private static final int NAG_AFTER = 3;   // s05 的提醒节奏，本篇不动

    private static final DateTimeFormatter SESSION_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FULL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private final HookRegistry hooks;
    private final TodoStore todos;
    private final SkillRegistry skills;
    private final Subagent subagent;
    private final Compactor compactor;
    private final MemoryStore memory;
    private final SystemPrompt prompt = new SystemPrompt();
    /** 会话级：进会话时算一次，中途不再变。所以它可以排在静态段后面 */
    private final String workspace;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个基础工具

        this.todos = new TodoStore(mapper);
        registry.register("todo_write", TodoStore.DESCRIPTION, todos.schema(), todos::update);

        // s07：启动时只把「目录」读进内存，正文还躺在磁盘上
        this.skills = new SkillRegistry(mapper,
                SkillLoader.loadAll(Path.of(System.getenv().getOrDefault("SKILL_DIR", "skills"))));
        System.out.println("   [skill] 技能目录 " + skills.size() + " 项：" + skills.names());

        // s09：两级记忆从磁盘读进来。它们不在 messages 里，所以压缩碰不到
        this.memory = MemoryStore.open();
        registry.register("memory_write", MemoryStore.WRITE_DESCRIPTION,
                memory.writeSchema(mapper),
                args -> memory.write(args.path("scope").asText("project"),
                        args.path("key").asText(""), args.path("text").asText("")));
        registry.register("memory_read", MemoryStore.READ_DESCRIPTION,
                memory.readSchema(mapper),
                args -> memory.read(args.path("key").asText("")));

        this.hooks = new HookRegistry();
        hooks.register("UserPromptSubmit", BuiltinHooks.contextInjectHook());
        hooks.register("UserPromptSubmit", BuiltinHooks.memoryHintHook());
        hooks.register("PreToolUse", BuiltinHooks.permissionHook(skills));
        hooks.register("PreToolUse", BuiltinHooks.logHook());
        hooks.register("PostToolUse", BuiltinHooks.largeOutputHook());
        hooks.register("Stop", BuiltinHooks.summaryHook());

        this.subagent = new Subagent(client, hooks);
        registry.register("task",
                "Launch a subagent to handle a self-contained subtask. "
                        + "The subagent has its own fresh context and returns only its final conclusion. "
                        + "Use it for exploration or multi-step work whose intermediate steps you do not need.",
                subagent.schema(),
                args -> subagent.run(args.path("description").asText("")));

        registry.register("skill", SkillRegistry.DESCRIPTION, skills.schema(),
                args -> skills.load(args.path("name").asText("")));

        this.compactor = new Compactor(client, this::liveState);
        this.workspace = "Working directory: " + Path.of("").toAbsolutePath()
                + " · shell: " + System.getenv().getOrDefault("SHELL", "sh")
                + " · session started " + LocalDateTime.now().format(SESSION_TS);
    }

    /**
     * 拼一份 system prompt。
     *
     * 每轮都调。三档频率的顺序由 SystemPrompt 保证，这里只负责提供段的内容。
     * naive 模式下顺序由这里决定——故意按「重要性」排，时间戳还放在最前面。
     */
    private SystemPrompt.Assembled buildPrompt() {
        List<SystemPrompt.Section> sections = new ArrayList<>();

        if (SystemPrompt.naive()) {
            // 反例：先把「现在几点」告诉模型。最直觉的写法，也是每轮字节都变的写法
            sections.add(new SystemPrompt.Section("clock", SystemPrompt.Kind.STATIC, 0,
                    "Current time: " + LocalDateTime.now().format(FULL_TS) + " (Asia/Shanghai)"));
        }
        sections.add(new SystemPrompt.Section("identity", SystemPrompt.Kind.STATIC, 10, IDENTITY));
        sections.add(new SystemPrompt.Section("tool-policy", SystemPrompt.Kind.STATIC, 20, POLICY));

        if (SystemPrompt.naive()) {
            // 反例：按「哪个更重要」排，记忆比技能重要所以放前面——看着合理，缓存全毁
            sections.add(new SystemPrompt.Section("memory", SystemPrompt.Kind.SESSION, 50,
                    memory.indexSection().strip()));
            sections.add(new SystemPrompt.Section("workspace", SystemPrompt.Kind.SESSION, 30, workspace));
            sections.add(new SystemPrompt.Section("skills", SystemPrompt.Kind.SESSION, 40,
                    skills.catalogSection().strip()));
        } else {
            sections.add(new SystemPrompt.Section("workspace", SystemPrompt.Kind.SESSION, 30, workspace));
            sections.add(new SystemPrompt.Section("skills", SystemPrompt.Kind.SESSION, 40,
                    skills.catalogSection().strip()));
            sections.add(new SystemPrompt.Section("memory", SystemPrompt.Kind.SESSION, 50,
                    memory.indexSection().strip()));
        }

        sections.add(new SystemPrompt.Section("todo", SystemPrompt.Kind.ROUND, 90, todoSection()));
        return prompt.assemble(sections);
    }

    /** 每轮都在变的段：待办看板。空了就不出这一段，而不是出一段「(empty)」 */
    private String todoSection() {
        String board = todos.board();
        if (board.isBlank() || board.startsWith("Progress: 0/0")) {
            return "";
        }
        return "Current plan (it changes as you work — treat it as the state you left things in):\n" + board;
    }

    /**
     * 压缩后要重新贴回上下文的那部分状态。
     *
     * 待办清单：每轮都在变，摘要只是某一刻的快照，靠不住。
     * 已加载技能：正文很可能正好被压掉，不提示一句，模型就会凭摘要硬着头皮往下做。
     */
    private String liveState() {
        StringBuilder sb = new StringBuilder();
        String board = todos.board();
        if (!board.isBlank() && !board.startsWith("Progress: 0/0")) {
            sb.append("当前待办清单：\n").append(board).append('\n');
        }
        if (!skills.loadedNames().isEmpty()) {
            sb.append("已加载技能：").append(String.join(", ", skills.loadedNames()))
                    .append("（技能正文如已不在上下文里，重新调 skill(name) 取回）");
        }
        return sb.toString().strip();
    }

    public void run(String query) {
        ArrayNode messages = mapper.createArrayNode();

        // 第一轮先拼一次。之后每轮重拼，但只有字节变了才写回 messages[0]
        SystemPrompt.Assembled first = buildPrompt();
        prompt.plan(first);
        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", first.text());
        messages.add(system);
        String lastSystem = first.text();

        String hint = hooks.trigger(Event.userPrompt(query));

        ObjectNode goal = mapper.createObjectNode();
        goal.put("role", "user");
        goal.put("content", query);
        messages.add(goal);

        if (hint != null) {
            ObjectNode injected = mapper.createObjectNode();
            injected.put("role", "user");
            injected.put("content", hint);
            messages.add(injected);
            System.out.println("   [HOOK] UserPromptSubmit: 注入记忆提示");
        }

        ArrayNode tools = registry.schemas();
        int toolCount = 0;
        int roundsSinceTodo = 0;

        while (true) {
            // s10：每轮重拼。拼一次很便宜，判错一次是整轮缓存白攒
            SystemPrompt.Assembled now = buildPrompt();
            if (!now.text().equals(lastSystem)) {
                system.put("content", now.text());
                lastSystem = now.text();
                System.out.println("   [prompt] 重写 messages[0]："
                        + (now.changed().isEmpty() ? "首次" : "变了 " + String.join("/", now.changed()))
                        + "｜前缀 " + now.prefixHash()
                        + (now.prefixReused() ? " 未变（缓存还能用）" : " 变了"));
            }

            // s08：进模型之前先看一眼体积。超了就把中间那段换成摘要
            compactor.maybeCompact(messages);

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
                compactor.report();
                memory.report();
                prompt.report();
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
        if ("memory_write".equals(name)) {
            return args.path("scope").asText("") + ":" + args.path("key").asText("");
        }
        if ("memory_read".equals(name)) {
            return "\"" + args.path("key").asText("") + "\"";
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
                : "记住：这个项目的接口错误返回固定是 {\"error\": {\"code\": ..., \"message\": ...}}，"
                        + "不是 OpenAI 那种 {\"error\": {\"type\": ...}}。"
                        + "然后读 data 下的日志，把最慢的一条挑出来。";
        new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
    }

    /** 静态段一：身份。发版才变，所以排最前面——它一变后面全部作废，得值这个价 */
    private static final String IDENTITY = """
            You are a coding agent working inside a repository. You inspect files, run commands and
            edit code to finish the task the user gives you.
            Prefer read_file / write_file / edit_file / glob over shell commands when possible.
            When the task is done, answer in plain text without calling a tool.
            """;

    /**
     * 静态段二：工具策略。
     *
     * 注意这里写的是「什么时候用」，不是「参数有哪些」——参数在 tools schema 里，模型能看到。
     * 同一件事写两遍不只是浪费 token：两边不一致的时候，模型不知道该信哪个。
     */
    private static final String POLICY = """
            For any multi-step task, call todo_write FIRST to lay out a plan, then mark each item
            in_progress / completed as you make progress. Keep the plan up to date.
            When a subtask would need a lot of exploration whose intermediate steps you do not care
            about, hand it to the task tool: the subagent has its own fresh context and returns only
            a short conclusion.
            The skills listed below are NOT loaded. When the work matches one of them, call skill(name)
            first and then follow what it returns.
            When the context gets long, older messages are replaced by a <summary>. Treat the summary
            as authoritative for what is already done, and re-read files or re-load skills when you
            need the exact details.
            The memory index below is one line per fact and is NOT loaded in full — call memory_read(key)
            when the work depends on the details. When you confirm something worth keeping for next
            time, or when the user tells you to remember something, call memory_write with ONE
            sentence-level fact: scope=project for repository facts, scope=user for personal
            preferences. Reuse the existing key when you are correcting a fact.
            """;
}
