package dev.lcc.s18;

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
 * s18: 主 Agent 循环（工作树隔离版）。
 *
 * 循环骨架还是一行没动。这一篇加的是成员那一侧的写权限：s15 起它一直封着，
 * 因为两个成员同时改同一个文件，后写的盖掉先写的，还没有报错。
 * 现在每个成员有一棵自己的 git worktree，写只落在自己那棵上，合不合由 lead 决定。
 *
 * 接缝在 Teammate 的构造函数里那一行 BuiltinTools.install(registry, cwd)：
 * 工具表从「进程的工作目录」变成「这棵树」。主循环完全不知道这件事。
 */
public class AgentLoop {
    private static final int NAG_AFTER = 3;   // s05 的提醒节奏，本篇不动

    private static final DateTimeFormatter SESSION_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FULL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private final HookRegistry hooks;
    private final TaskStore tasks;
    private final SkillRegistry skills;
    private final Subagent subagent;
    private final Compactor compactor;
    private final MemoryStore memory;
    private final SystemPrompt prompt = new SystemPrompt();
    /** s15：成员表就住在这里。循环不持有任何一个成员，只持有这张表 */
    private final Team team;
    /** s18：沙箱和树。spawn 开树、merge 合并，都走它 */
    private final Worktree worktree;
    /** s17：自主驱动。它不在循环里，循环只留一个入口给它 */
    private final Autonomy autonomy;
    /** 会话级：进会话时算一次，中途不再变。所以它可以排在静态段后面 */
    private final String workspace;
    /** s12：连续相同调用的检测。跨轮累计，所以放在循环外面 */
    private final LoopDetector loop = new LoopDetector();
    private int toolErrors;
    private int loopStops;
    /** 上下文超长只自动救一次：救完还超，说明压缩没起作用，再试就是死循环 */
    private boolean overflowRetried;

    public AgentLoop(OpenAICompatClient client) {
        this.client = client;
        this.mapper = client.mapper();
        this.registry = new ToolRegistry(mapper);
        BuiltinTools.install(registry);   // 5 个基础工具

        // s12：任务从内存挪到磁盘。启动时读回来的 in_progress 会被降级成 orphaned
        this.tasks = TaskStore.open(mapper);
        System.out.println("   [task] " + tasks.report());
        registry.register("task_create",
                "Create a task on disk. blockedBy lists task ids that must be completed first. "
                        + "priority is an integer, bigger runs first — the autonomous driver picks "
                        + "by it, so put the blocking work at the top.",
                taskCreateSchema(mapper),
                args -> {
                    try {
                        TaskStore.Task t = tasks.create(args.path("subject").asText(""),
                                strings(args.path("blockedBy")), args.path("priority").asInt(0));
                        return "Created " + t.id() + " p" + t.priority() + " " + t.subject()
                                + (t.blockedBy().isEmpty() ? "" : "（等 " + String.join(",", t.blockedBy()) + "）");
                    } catch (RuntimeException e) {
                        return "(error: " + e.getMessage() + ")";
                    }
                });
        registry.register("task_update",
                "Change a task's status. Starting a task whose blockedBy is not completed is rejected.",
                taskUpdateSchema(mapper),
                args -> {
                    try {
                        String id = args.path("id").asText("");
                        if (args.has("blockedBy")) {
                            tasks.rewire(id, strings(args.path("blockedBy")));
                        }
                        TaskStore.Task t = tasks.update(id,
                                TaskStore.Status.valueOf(args.path("status").asText("PENDING").toUpperCase()),
                                args.path("owner").asText(""));
                        return "Updated " + t.id() + " → " + t.status()
                                + (t.owner().isBlank() ? "" : "（" + t.owner() + "）");
                    } catch (RuntimeException e) {
                        return "(error: " + e.getMessage() + ")";
                    }
                });
        registry.register("task_list",
                "List tasks with their status and dependencies.",
                emptySchema(mapper),
                args -> tasks.all().isEmpty() ? "(no tasks)"
                        : tasks.all().stream()
                        .map(t -> t.id() + " [" + t.status() + "] " + t.subject()
                                + (t.blockedBy().isEmpty() ? "" : " ← " + String.join(",", t.blockedBy())))
                        .reduce((a, b) -> a + "\n" + b).orElse(""));

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

        // s18：沙箱先立起来。没有仓库、没有第一次提交，git worktree add 会直接失败
        this.worktree = new Worktree();
        System.out.println("   [worktree] " + worktree.ensureRepo());
        System.out.println("   [worktree] 模式 " + (Worktree.NAIVE ? "naive（不用树，共写主树）" : "checked")
                + "，沙箱 " + Worktree.SANDBOX);

        // s15：团队。三个工具，一个 tick——循环里没有别的改动
        this.team = new Team(mapper, client, hooks, tasks, worktree);
        registry.register("team_spawn",
                "Spawn a named teammate with its own context and its own inbox. "
                        + "The teammate reads files and claims tasks on the shared board, then reports "
                        + "one conclusion back to you. It does NOT reply immediately: nothing happens "
                        + "until the next round.",
                teamSpawnSchema(mapper),
                args -> team.spawn(args.path("name").asText(""),
                        args.path("role").asText(""), args.path("prompt").asText("")));
        registry.register("send_message",
                "Send an envelope to a teammate, or to every teammate with to=\"all\". "
                        + "type=request obliges the receiver to answer with type=response and replyTo "
                        + "pointing back at your request id; an unanswered request lands in the dead "
                        + "letter file after PROTOCOL_TIMEOUT_MS. type=broadcast reaches everyone. "
                        + "The message is read at the start of that teammate's NEXT step, never during "
                        + "this round.",
                sendMessageSchema(mapper),
                args -> team.send(Team.LEAD,
                        args.path("to").asText(""),
                        args.path("type").asText(""),
                        args.path("text").asText(""),
                        args.path("replyTo").asText("")));
        registry.register("task_handoff",
                "Transfer a task to another teammate. The owner does NOT change until the receiver "
                        + "answers task_update(id, status=ACCEPT or REJECT). Handing off a task that "
                        + "does not exist is rejected.",
                handoffSchema(mapper),
                args -> team.handoff(Team.LEAD,
                        args.path("taskId").asText(""),
                        args.path("to").asText("")));
        registry.register("team_status",
                "Show every teammate: what it is doing right now, and how many messages in its inbox "
                        + "it has not read yet. Use it when you have just sent something and want to "
                        + "know whether it has been picked up.",
                emptySchema(mapper),
                args -> team.status().isBlank() ? "(no teammates)" : team.status());

        // s18：两棵树之间的账。create 不单独开一个工具——team_spawn 的时候顺手就开了，
        // 让模型先 spawn 再 create 只会多一次「忘了 create」的机会
        registry.register("worktree_list",
                "List the git worktrees that are currently open: one per teammate that has not been "
                        + "merged yet. Read from 'git worktree list', so it also shows a tree whose "
                        + "directory was deleted by hand.",
                emptySchema(mapper),
                args -> worktree.printList());
        registry.register("worktree_merge",
                "Merge a teammate's branch (agent/<name>) back into the main tree with git merge --no-ff. "
                        + "It commits that worktree's changes first — a worktree that was edited but "
                        + "never committed would merge as nothing. On conflict it returns git's CONFLICT "
                        + "lines and the conflicting file names, aborts the merge so the main tree is "
                        + "exactly as it was, and leaves the worktree in place for you to fix or drop. "
                        + "On success both the worktree and the branch are removed.",
                worktreeNameSchema(mapper),
                args -> worktree.merge(args.path("name").asText("")));
        registry.register("worktree_remove",
                "Throw a teammate's worktree away without merging it. Use it when you decide that "
                        + "side's version should not land (for example a conflict you do not want to "
                        + "resolve). The branch agent/<name> goes with it.",
                worktreeNameSchema(mapper),
                args -> worktree.remove(args.path("name").asText("")));

        // s17：自主驱动。三个工具，一个循环在 Autonomy 里——主循环只留一个入口
        this.autonomy = new Autonomy(client, hooks, tasks);
        registry.register("autonomy_start",
                "Hand a goal to the autonomous driver and let it work on its own. It picks ready "
                        + "tasks by priority, runs each one in its own session, verifies the result, "
                        + "and stops by itself: after STALL_ROUNDS consecutive rounds that changed "
                        + "nothing on disk, or when a budget (rounds / tokens) runs out. This call "
                        + "blocks until it stops and returns why. Do not drive it round by round.",
                autonomyStartSchema(mapper),
                args -> autonomy.start(args.path("goal").asText("")));
        registry.register("autonomy_status",
                "How far the autonomous driver has got: which round, how much token budget is left, "
                        + "how many consecutive rounds without progress, and why it stopped last time.",
                emptySchema(mapper),
                args -> autonomy.status());
        registry.register("autonomy_stop",
                "Ask the autonomous driver to stop. It takes effect between rounds — a round already "
                        + "running is not interrupted, so the task board never gets a half-written entry.",
                emptySchema(mapper),
                args -> autonomy.stop());

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

        sections.add(new SystemPrompt.Section("tasks", SystemPrompt.Kind.ROUND, 90, taskSection()));
        // s15：队形。和 team_status 返回的是同一份文字，区别只在「什么时候看」：
        // 这一段是这一轮开头的快照，投完消息想知道谁还没读，得再问一次工具
        sections.add(new SystemPrompt.Section("team", SystemPrompt.Kind.ROUND, 95, team.status()));
        // s18：树的账。谁还没合、主树脏没脏，每轮开头都摆在这里
        sections.add(new SystemPrompt.Section("worktrees", SystemPrompt.Kind.ROUND, 96,
                worktree.statusLine()));
        return prompt.assemble(sections);
    }

    /** 每轮都在变的段：待办看板。空了就不出这一段，而不是出一段「(empty)」 */
    private String taskSection() {
        String board = tasks.board();
        if (board.isBlank()) {
            return "";
        }
        return "Tasks on disk (they survive restarts; only the \"ready\" ones are yours to start):\n" + board;
    }

    /**
     * 压缩后要重新贴回上下文的那部分状态。
     *
     * 待办清单：每轮都在变，摘要只是某一刻的快照，靠不住。
     * 已加载技能：正文很可能正好被压掉，不提示一句，模型就会凭摘要硬着头皮往下做。
     */
    private String liveState() {
        StringBuilder sb = new StringBuilder();
        String board = tasks.board();
        if (!board.isBlank()) {
            sb.append("当前任务板：\n").append(board).append('\n');
        }
        if (!skills.loadedNames().isEmpty()) {
            sb.append("已加载技能：").append(String.join(", ", skills.loadedNames()))
                    .append("（技能正文如已不在上下文里，重新调 skill(name) 取回）");
        }
        return sb.toString().strip();
    }

    // ---------- 工具 schema ----------

    private static ObjectNode taskCreateSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("subject", prop(m, "string", "One line describing what must be done"));
        props.set("blockedBy", prop(m, "array", "Optional list of task ids that must be completed first"));
        props.set("priority", prop(m, "integer", "Optional int, bigger runs first (default 0)"));
        schema.set("properties", props);
        schema.set("required", m.createArrayNode().add("subject"));
        return schema;
    }

    private static ObjectNode taskUpdateSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("id", prop(m, "string", "Task id, e.g. T2"));
        props.set("status", prop(m, "string", "PENDING / IN_PROGRESS / COMPLETED / ORPHANED"));
        props.set("owner", prop(m, "string", "Optional owner name"));
        props.set("blockedBy", prop(m, "array", "Optional: replace this task's dependencies (cycle-checked)"));
        schema.set("properties", props);
        var req = m.createArrayNode();
        req.add("id");
        req.add("status");
        schema.set("required", req);
        return schema;
    }

    private static ObjectNode teamSpawnSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("name", prop(m, "string", "Short unique name, also used as the mailbox directory"));
        props.set("role", prop(m, "string", "One line: what this teammate is responsible for"));
        props.set("prompt", prop(m, "string", "Its first user message — the task, stated plainly"));
        schema.set("properties", props);
        var req = m.createArrayNode();
        req.add("name");
        req.add("role");
        req.add("prompt");
        schema.set("required", req);
        return schema;
    }

    private static ObjectNode sendMessageSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("to", prop(m, "string", "A teammate name, or \"all\" to broadcast"));
        props.set("text", prop(m, "string", "The message. Be specific — it is read next round, not now"));
        props.set("type", prop(m, "string",
                "request (needs an answer) / response (answer with replyTo) / broadcast / handoff; omit for plain"));
        props.set("replyTo", prop(m, "string", "For type=response: the request id you are answering"));
        schema.set("properties", props);
        var req = m.createArrayNode();
        req.add("to");
        req.add("text");
        schema.set("required", req);
        return schema;
    }

    private static ObjectNode handoffSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("taskId", prop(m, "string", "Task id to transfer, e.g. T2"));
        props.set("to", prop(m, "string", "The teammate who takes over"));
        schema.set("properties", props);
        var req = m.createArrayNode();
        req.add("taskId");
        req.add("to");
        schema.set("required", req);
        return schema;
    }

    private static ObjectNode worktreeNameSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("name", prop(m, "string",
                "Teammate name — the same string you gave team_spawn; it is the worktree directory name"));
        schema.set("properties", props);
        schema.set("required", m.createArrayNode().add("name"));
        return schema;
    }

    private static ObjectNode autonomyStartSchema(ObjectMapper m) {
        ObjectNode schema = emptySchema(m);
        ObjectNode props = m.createObjectNode();
        props.set("goal", prop(m, "string",
                "What you want done, in one or two sentences. The driver reads the task board "
                        + "itself — do not list the tasks here."));
        schema.set("properties", props);
        schema.set("required", m.createArrayNode().add("goal"));
        return schema;
    }

    static ObjectNode emptySchema(ObjectMapper m) {
        var schema = m.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", m.createObjectNode());
        schema.set("required", m.createArrayNode());
        return schema;
    }

    static ObjectNode prop(ObjectMapper m, String type, String desc) {
        var p = m.createObjectNode();
        p.put("type", type);
        p.put("description", desc);
        return p;
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
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
            // s15：每一步先让团队走一格。放在这里而不是循环末尾，
            // 是为了让「这一轮发的消息」一定落在「下一轮的 tick」里被读到
            for (Mailbox.Msg m : team.tick()) {
                ObjectNode from = mapper.createObjectNode();
                from.put("role", "user");
                from.put("content", "<team-inbox from=\"" + m.from() + "\" at=\"" + m.at() + "\">"
                        + m.text() + "</team-inbox>");
                messages.add(from);
                System.out.println("   [team] " + m.from() + " 报回：" + clip(m.text(), 70));
            }

            // s12：每轮重拼。拼一次很便宜，判错一次是整轮缓存白攒
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
                        "<reminder>Update the task board: mark finished tasks completed with task_update, "
                                + "then start a ready one. Do not start a blocked task.</reminder>");
                messages.add(reminder);
                System.out.println("\n   [NAG] 连续 " + roundsSinceTodo + " 轮没动任务板，注入提醒");
                roundsSinceTodo = 0;
            }

            JsonNode response;
            try {
                response = client.createChatCompletion(messages, tools);
            } catch (ApiException e) {
                // 服务端说「太长了」。压缩是循环才有的能力，客户端不该自己乱发
                if (e.failure() == Failure.CONTEXT_OVERFLOW && !overflowRetried) {
                    overflowRetried = true;
                    System.out.println("   [recover] 服务端拒收：上下文超长（HTTP " + e.status()
                            + "），不等估算，直接压一次再发");
                    if (!compactor.forceCompact(messages)) {
                        System.out.println("   [recover] 压缩没成功，没有别的路了");
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
            overflowRetried = false;

            JsonNode choice = response.path("choices").path(0);
            JsonNode message = choice.path("message");
            messages.add(message);

            JsonNode toolCalls = message.path("tool_calls");
            boolean hasTools = toolCalls.isArray() && !toolCalls.isEmpty();

            if (hasTools && usedTask(toolCalls)) {
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
                report();
                return;
            }

            LoopDetector.Decision decision = LoopDetector.Decision.OK;
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                JsonNode args = parseArgs(call.path("function").path("arguments"));

                System.out.println("\n→ " + name + "(" + brief(name, args) + ")");

                LoopDetector.Decision d = loop.observe(name, args);
                if (d.ordinal() > decision.ordinal()) {
                    decision = d;
                }
                if (d == LoopDetector.Decision.STOP) {
                    // 这一轮不再执行，但 tool_call 必须逐条回应，否则下一次请求的配对是坏的。
                    // 所以这里给的是「没执行」而不是「跳过这条消息」。
                    ObjectNode skipped = mapper.createObjectNode();
                    skipped.put("role", "tool");
                    skipped.put("tool_call_id", id);
                    skipped.put("content", "(not executed: repeated call detected)");
                    messages.add(skipped);
                    System.out.println("   [loop] 这次调用不执行，仍回一条 tool 消息（配对不能坏）");
                    continue;
                }

                String blocked = hooks.trigger(Event.preTool(name, args));
                if (blocked != null) {
                    ObjectNode denied = mapper.createObjectNode();
                    denied.put("role", "tool");
                    denied.put("tool_call_id", id);
                    denied.put("content", blocked);
                    messages.add(denied);
                    continue;
                }

                String output;
                boolean failed;
                try {
                    output = registry.dispatch(name, args);
                    failed = looksLikeError(output);
                } catch (RuntimeException e) {
                    // 工具抛异常是最容易把整个循环带走的地方：一个空指针就让整轮对话结束。
                    // 异常不是给程序的信号，是给模型的输入——它会读，然后换个做法
                    output = "(error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
                    failed = true;
                }
                if (failed) {
                    toolErrors++;
                    output = toolError(name, output);
                }
                toolCount++;
                hooks.trigger(Event.postTool(name, args, output));

                System.out.println(output);
                ObjectNode toolResult = mapper.createObjectNode();
                toolResult.put("role", "tool");
                toolResult.put("tool_call_id", id);
                toolResult.put("content", output);
                messages.add(toolResult);
            }

            if (decision == LoopDetector.Decision.WARN) {
                ObjectNode warn = mapper.createObjectNode();
                warn.put("role", "user");
                warn.put("content", "<loop-warning>你已经连续 " + loop.streak() + " 次用完全相同的参数调用 "
                        + loop.lastCall() + "，结果不会变。停下来看看错误信息，换个参数或换个工具；"
                        + "如果你认为必须原样重试，先说明为什么这一次会不一样。</loop-warning>");
                messages.add(warn);
                System.out.println("\n   [loop] 连续 " + loop.streak() + " 次相同调用，注入提醒");
            }

            if (decision == LoopDetector.Decision.STOP) {
                loopStops++;
                System.out.println("\n   [loop] 连续 " + loop.streak() + " 次相同调用，中止这一轮任务");
                report();
                return;
            }
        }
    }

    /** 工具的失败在 s12 之前只是普通字符串，现在要能被分辨出来 */
    private static boolean looksLikeError(String output) {
        if (output == null) {
            return false;
        }
        String s = output.strip();
        return s.startsWith("(error:") || s.startsWith("Error:") || s.startsWith("(command timed out)");
    }

    private static String toolError(String name, String output) {
        return "<tool-error tool=\"" + name + "\">" + output
                + "\n这次调用失败了。按错误信息改参数或换工具，别把同样的调用再发一次。</tool-error>";
    }

    private void report() {
        prompt.report();
        compactor.report();
        memory.report();
        client.retry().report();
        System.out.println("   [task] " + tasks.report());
        System.out.println("   [team] " + team.report());
        System.out.println("   [worktree] " + worktree.statusLine());
        System.out.println("   [autonomy] " + autonomy.status());
        System.out.println("   [recover] 工具错误 " + toolErrors + " 次（已回灌给模型），"
                + "死循环中止 " + loopStops + " 次，循环提醒 " + loop.warned() + " 次");
    }

    private boolean usedTask(JsonNode toolCalls) {
        for (JsonNode call : toolCalls) {
            if ("task_update".equals(call.path("function").path("name").asText())
                    || "task_create".equals(call.path("function").path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 打印参数时压成一行摘要 */
    private String brief(String name, JsonNode args) {
        if ("task_create".equals(name)) {
            return "\"" + args.path("subject").asText("") + "\"";
        }
        if ("task_update".equals(name)) {
            return args.path("id").asText("") + " → " + args.path("status").asText("");
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
        if ("team_spawn".equals(name)) {
            return args.path("name").asText("") + "（" + args.path("role").asText("") + "）";
        }
        if ("send_message".equals(name)) {
            return args.path("type").asText("plain") + " → " + args.path("to").asText("")
                    + "：" + clip(args.path("text").asText(""), 40);
        }
        if ("task_handoff".equals(name)) {
            return args.path("taskId").asText("") + " → " + args.path("to").asText("");
        }
        if ("autonomy_start".equals(name)) {
            return "\"" + args.path("goal").asText("") + "\"";
        }
        if ("worktree_merge".equals(name) || "worktree_remove".equals(name)) {
            return args.path("name").asText("");
        }
        return args.toString();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
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
                : "沙箱仓库里 notes/plan.md 的「步骤 1」要补一句为什么。让 alice 和 bob 各写一版："
                        + "alice 补「先核对版本号」，bob 补「先备份 orders 表」。两人都改同一行。"
                        + "写完先让我看主树是不是一条没动，再依次合回来。合不进去的那一版别硬合。";
        try {
            new AgentLoop(new OpenAICompatClient(baseUrl, apiKey, model)).run(query);
        } catch (ApiException e) {
            // FATAL 与「重试到底还是失败」都到这里：不装作成功，也不抛栈给用户看
            System.out.println("\n✗ 放弃：" + e.failure() + "（HTTP " + e.status() + "）" + e.getMessage());
            System.out.println("  这一类错误重试没有意义，停下来比继续烧钱好。");
        }
    }

    /** 静态段一：身份。发版才变，所以排最前面——它一变后面全部作废，得值这个价 */
    private static final String IDENTITY = """
            You are the lead of a small agent team working inside a repository. You inspect files,
            run commands and edit code to finish the task the user gives you, and you hand the
            parallel parts to teammates.
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
            For any multi-step task, call task_create FIRST to lay the work out on disk, then move each
            task PENDING → IN_PROGRESS → COMPLETED with task_update. Tasks with blockedBy cannot be
            started until their dependencies are completed — the tool rejects it, so read the board
            before picking one. The board below is state, not a suggestion.
            When a subtask would need a lot of exploration whose intermediate steps you do not care
            about, hand it to the task tool: the subagent has its own fresh context and returns only
            a short conclusion.
            When several subtasks can be done at the same time by different owners, use team_spawn
            instead: a teammate stays alive, has its own inbox, and claims a task on the same board.
            You do not get its result in this round — it runs one step per round, so its conclusion
            arrives as a message one or more rounds later. Do not wait for it; keep working on
            something that does not depend on it, then read team_status.
            Each teammate writes inside its own git worktree (spawning it created one). A teammate
            editing notes/plan.md changes only its own copy, so two of them can touch the same line
            and neither loses work. Nothing of theirs is in the main tree until you call
            worktree_merge(name): that commits their tree, merges agent/<name> with --no-ff, and on
            success deletes both. If the merge conflicts, you get git's CONFLICT lines and the file
            names back and the main tree is left exactly as it was — the worktree stays so you can
            ask its owner to fix it, or worktree_remove it if you want the other side. Do not edit
            the main tree yourself to "fix" a conflict you just learned about; decide whose version
            wins first, then merge or remove.
            Messages are envelopes. A request you send obliges the receiver to answer with
            type=response and replyTo pointing back at your request id; if it does not answer
            before the deadline, the request lands in your dead.jsonl and team_status shows it.
            A response you receive is trustworthy ONLY because replyTo was checked — unmatched
            replies are rejected as orphan_reply.
            To move a task to another teammate, task_handoff: the owner changes only after the
            receiver answers ACCEPT / REJECT with task_update, not when you send the handoff.
            When the work is a whole goal rather than one step you want to watch, hand it to
            autonomy_start(goal) instead of driving it yourself. The driver picks ready tasks by
            priority, runs each in its own session, and stops on its own — after STALL_ROUNDS
            consecutive rounds with nothing changed on disk, or when a budget runs out. It returns
            the reason it stopped; read that before you decide to run it again.
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
