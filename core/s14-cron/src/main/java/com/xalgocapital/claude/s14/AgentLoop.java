package com.xalgocapital.claude.s14;

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
 * s14: 主 Agent 循环。
 *
 * 相对 s13 只动了一处：注册三个定时任务工具 cron_create / cron_list / cron_delete，
 * 并把 CronScheduler 的守护线程拉起来。循环骨架一行没动——到点触发不经过这里，
 * 调度器自己起会话，自己落日志。这正是和 s13 的分工：
 *   s13 的后台任务：模型踢一脚才存在，结果由 poll() 送回主会话的 messages
 *   s14 的定时任务：没人踢，到点自己跑，结果落在 .cron/<id>.log 等人来看
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
    /** s13：后台任务表。进程、输出文件、offset 都在里面 */
    private final BackgroundTasks bg;
    /** s14：定时任务表 + 调度器。表在磁盘上，调度在守护线程里 */
    private final CronStore cronStore;
    private final CronScheduler cronScheduler;
    /** messages = 正常写法；system = 反例，把完成通知写进 system */
    private final String bgNoticeMode = System.getenv().getOrDefault("BG_NOTICE", "messages");
    /** 反例模式下攒起来的通知，拼进 system 的那一段 */
    private final List<String> bgSystemNotices = new ArrayList<>();
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

        // s13：三个后台任务工具。start 立刻返回，输出落盘，靠 poll 收尾
        this.bg = new BackgroundTasks(mapper);
        registry.register("bash_bg",
                "Start a shell command in the background and return immediately. "
                        + "The loop keeps running while it works; read its output later with bash_output. "
                        + "Use it for commands that take minutes (test suites, builds, crawls).",
                bgStartSchema(mapper),
                args -> bg.start(args.path("command").asText(""),
                        args.has("timeoutSec") ? args.path("timeoutSec").asInt() : 0));
        registry.register("bash_output",
                "Read the output a background task produced SINCE YOUR LAST READ. "
                        + "Only new lines are returned; do not expect the whole log again.",
                bgIdSchema(mapper, "id", "Background task id, e.g. bg_1"),
                args -> bg.output(args.path("id").asText("")));
        registry.register("kill_task",
                "Kill a running background task. Its exit code is recorded.",
                bgIdSchema(mapper, "id", "Background task id, e.g. bg_1"),
                args -> bg.kill(args.path("id").asText("")));

        // s14：定时任务。表达式解析先自检一遍——解析是这一章手写的，错了后面全白搭
        Cron.selfCheck();
        this.cronStore = CronStore.open(mapper);
        this.cronScheduler = new CronScheduler(client, cronStore,
                Path.of(System.getenv().getOrDefault("CRON_DIR", ".cron")));
        cronStore.attachClock(cronScheduler::now);   // 建任务时算第一个 nextRun 用
        cronScheduler.start();
        registry.register("cron_create",
                "Create a scheduled task. expr is a 5-field cron expression (minute hour day month "
                        + "weekday, supporting *, a, a-b, */n and comma lists). At each fire time the "
                        + "scheduler starts a FRESH session whose only context is prompt — it does not "
                        + "share this conversation, and its result goes to .cron/<id>.log.",
                cronCreateSchema(mapper),
                args -> {
                    try {
                        CronStore.Job job = cronStore.create(args.path("expr").asText(""),
                                args.path("prompt").asText(""));
                        return "Created " + job.id + " `" + job.expr + "` next="
                                + (job.nextRun == null ? "?" : job.nextRun.replace('T', ' '))
                                + "；结果会写到 .cron/" + job.id + ".log";
                    } catch (RuntimeException e) {
                        return "(error: " + e.getMessage() + ")";
                    }
                });
        registry.register("cron_list",
                "List scheduled tasks with their expression, next fire time and run counters.",
                emptySchema(mapper),
                args -> cronStore.listText());
        registry.register("cron_delete",
                "Delete a scheduled task by id. It will never fire again.",
                bgIdSchema(mapper, "id", "Cron job id, e.g. cron_2"),
                args -> {
                    try {
                        CronStore.Job gone = cronStore.delete(args.path("id").asText(""));
                        return "Deleted " + gone.id + " `" + gone.expr + "`，不会再触发";
                    } catch (RuntimeException e) {
                        return "(error: " + e.getMessage() + ")";
                    }
                });

        // s12：任务从内存挪到磁盘。启动时读回来的 in_progress 会被降级成 orphaned
        this.tasks = TaskStore.open(mapper);
        System.out.println("   [task] " + tasks.report());
        registry.register("task_create",
                "Create a task on disk. blockedBy lists task ids that must be completed first.",
                taskCreateSchema(mapper),
                args -> {
                    try {
                        TaskStore.Task t = tasks.create(args.path("subject").asText(""),
                                strings(args.path("blockedBy")));
                        return "Created " + t.id() + " " + t.subject()
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

        if ("system".equals(bgNoticeMode) && !bgSystemNotices.isEmpty()) {
            // 反例：把它当成 system 的开头。通知是「模型必须看到的信息」，放最前面最不容易漏读。
            // 这个直觉在 s10 的排序规则里是灾难：它每变一次，后面 4659 个字符全部重付
            sections.add(new SystemPrompt.Section("background", SystemPrompt.Kind.STATIC, 0,
                    "Background tasks that finished recently:\n" + String.join("\n", bgSystemNotices)));
        }
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

    // ---------- s14 的工具 schema ----------

    private static com.fasterxml.jackson.databind.node.ObjectNode cronCreateSchema(
            com.fasterxml.jackson.databind.ObjectMapper m) {
        var schema = emptySchema(m);
        var props = m.createObjectNode();
        props.set("expr", prop(m, "string",
                "5-field cron expression: minute hour day month weekday. e.g. */5 * * * *, 0 9 * * 1-5"));
        props.set("prompt", prop(m, "string",
                "The whole task for the fresh session started at each fire time"));
        schema.set("properties", props);
        schema.set("required", m.createArrayNode().add("expr").add("prompt"));
        return schema;
    }

    // ---------- s13 的工具 schema ----------

    private static com.fasterxml.jackson.databind.node.ObjectNode bgStartSchema(
            com.fasterxml.jackson.databind.ObjectMapper m) {
        var schema = emptySchema(m);
        var props = m.createObjectNode();
        props.set("command", prop(m, "string", "The shell command to run in the background"));
        props.set("timeoutSec", prop(m, "integer", "Optional: kill it after this many seconds (default 600)"));
        schema.set("properties", props);
        schema.set("required", m.createArrayNode().add("command"));
        return schema;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode bgIdSchema(
            com.fasterxml.jackson.databind.ObjectMapper m, String name, String desc) {
        var schema = emptySchema(m);
        schema.set("properties", m.createObjectNode().set(name, prop(m, "string", desc)));
        schema.set("required", m.createArrayNode().add(name));
        return schema;
    }

    // ---------- s12 的工具 schema ----------

    private static com.fasterxml.jackson.databind.node.ObjectNode taskCreateSchema(
            com.fasterxml.jackson.databind.ObjectMapper m) {
        var schema = emptySchema(m);
        var props = m.createObjectNode();
        props.set("subject", prop(m, "string", "One line describing what must be done"));
        props.set("blockedBy", prop(m, "array", "Optional list of task ids that must be completed first"));
        schema.set("properties", props);
        schema.set("required", m.createArrayNode().add("subject"));
        return schema;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode taskUpdateSchema(
            com.fasterxml.jackson.databind.ObjectMapper m) {
        var schema = emptySchema(m);
        var props = m.createObjectNode();
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

    private static com.fasterxml.jackson.databind.node.ObjectNode emptySchema(
            com.fasterxml.jackson.databind.ObjectMapper m) {
        var schema = m.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", m.createObjectNode());
        schema.set("required", m.createArrayNode());
        return schema;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode prop(
            com.fasterxml.jackson.databind.ObjectMapper m, String type, String desc) {
        var p = m.createObjectNode();
        p.put("type", type);
        p.put("description", desc);
        return p;
    }

    private static List<String> strings(JsonNode arr) {
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
            // s13：每轮请求前收一次。进程不会回调你，不看就永远不知道它结束了
            List<BackgroundTasks.Finished> bgDone = bg.poll();
            List<String> bgTexts = new ArrayList<>();
            for (BackgroundTasks.Finished f : bgDone) {
                bgTexts.add(bg.notice(f));
            }
            if ("system".equals(bgNoticeMode)) {
                bgSystemNotices.addAll(bgTexts);   // 反例：拼进下面那次 buildPrompt
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

            // s13：结束通知走 messages。一条 user 消息，system 一个字节不动，前缀缓存还能用。
            // 消息里的内容下几轮会一直留在上下文里，这就是「模型知道它跑完了」的全部来源
            if (!bgTexts.isEmpty() && !"system".equals(bgNoticeMode)) {
                ObjectNode notice = mapper.createObjectNode();
                notice.put("role", "user");
                notice.put("content", String.join("\n\n", bgTexts));
                messages.add(notice);
                System.out.println("\n   [bg] " + bgDone.size() + " 个任务结束，通知作为 user 消息注入"
                        + "（messages " + messages.size() + " 条，system " + lastSystem.length() + " 字符未变）");
            } else if (!bgTexts.isEmpty()) {
                System.out.println("\n   [bg] " + bgDone.size() + " 个任务结束，通知被拼进 system"
                        + "（反例：前缀作废）");
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
        System.out.println("   " + bg.report());
        System.out.println("   " + cronScheduler.report());
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
        if ("bash_bg".equals(name)) {
            return "\"" + args.path("command").asText("") + "\""
                    + (args.has("timeoutSec") ? " timeout " + args.path("timeoutSec").asInt() + "s" : "");
        }
        if ("bash_output".equals(name) || "kill_task".equals(name)) {
            return args.path("id").asText("");
        }
        if ("cron_create".equals(name)) {
            return "`" + args.path("expr").asText("") + "`";
        }
        if ("cron_delete".equals(name)) {
            return args.path("id").asText("");
        }
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
                : "跑一遍完整测试，同时把 README 里过时的命令改掉。测试要三分钟，别干等。";
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
            For any multi-step task, call task_create FIRST to lay the work out on disk, then move each
            task PENDING → IN_PROGRESS → COMPLETED with task_update. Tasks with blockedBy cannot be
            started until their dependencies are completed — the tool rejects it, so read the board
            before picking one. The board below is state, not a suggestion.
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
            A command that takes minutes (test suite, build, crawl) must not go through bash: bash
            blocks the loop until it returns. Use bash_bg, keep working on something else, and call
            bash_output(id) later — it returns ONLY the lines produced since your last read, so do not
            expect the whole log again. A finished task is reported to you automatically as a message;
            there is no need to poll bash_output in a loop just to check. Kill what you no longer
            need with kill_task.
            """;
}
