package dev.lcc.s17;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * s17：自主驱动。
 *
 * s16 的成员是被 tick() 推着走的：lead 调一次 tick，每个活着的成员走一步；
 * lead 不调，整个团队停在那儿。这一篇换一种驱动方式——给一个目标，
 * 它自己决定下一步做什么，做到自己认为可以停为止。
 *
 * 每一轮三件事，顺序不能换：
 *   挑活 从 ready 的任务里按 priority 挑一条（同优先级按 id 升序）
 *   干活 交给一个独立会话（system 只有目标、这一条任务、验收命令）
 *   记账 比任务状态、比 note、比工作目录的 mtime 快照
 *
 * 章节的核心不是「能一直跑」，是**什么时候停**。停的判据写在这里，不交给模型：
 * 连续 STALL_ROUNDS 轮「状态没变、快照没变、note 没变」就停。
 * 交给模型判断的话，它会告诉你它在思考，而磁盘上什么都没有。
 */
public final class Autonomy {
    /** 连续几轮无进展就停。3 是拍的：一轮可能只是读文件，两轮可能还在想，三轮没落地就是没落地 */
    static final int STALL_ROUNDS = intEnv("AUTONOMY_STALL_ROUNDS", 3);
    /** 轮次预算。不设这个，停滞判据失效时就没人踩刹车了 */
    static final int MAX_ROUNDS = intEnv("AUTONOMY_MAX_ROUNDS", 12);
    /** token 预算。按 system + messages 估的，不是账单上的数 */
    static final int MAX_TOKENS = intEnv("AUTONOMY_MAX_TOKENS", 60000);
    /** 反例开关：naive = 去掉停滞判据，只靠两个预算兜底 */
    static final boolean NAIVE = "naive".equals(System.getenv().getOrDefault("AUTONOMY_MODE", "checked"));
    /** 一个 worker 最多走几步。它自己说完了就提前结束，说不完就到这里为止 */
    static final int WORKER_STEPS = intEnv("AUTONOMY_WORKER_STEPS", 6);
    static final String OWNER = "autonomy";

    /** 优先级大的先做；一样大按 id 的数字部分升序（T2 在 T10 前面） */
    private static final Comparator<TaskStore.Task> BY_PRIORITY =
            Comparator.comparingInt(TaskStore.Task::priority).reversed()
                    .thenComparingInt(t -> idNum(t.id()));

    private final OpenAICompatClient client;
    private final ObjectMapper mapper;
    private final HookRegistry hooks;
    private final TaskStore tasks;
    private final ToolRegistry workerTools;
    private final Path workspace;
    private final String verifyCmd;

    private String goal = "";
    private boolean running;
    private volatile boolean stopRequested;
    private int round;          // 已经跑完的轮数
    private int stall;          // 连续无进展的轮数
    private int tokens;         // 估算的累计 token
    private int completed;
    private int bounces;
    private String stopReason = "";

    public Autonomy(OpenAICompatClient client, HookRegistry hooks, TaskStore tasks) {
        this.client = client;
        this.mapper = client.mapper();
        this.hooks = hooks;
        this.tasks = tasks;
        this.workerTools = new ToolRegistry(mapper);
        // worker 拿到的是全套 5 个工具：它要真的能改文件，否则「有进展」永远是 0
        BuiltinTools.install(workerTools);
        this.workspace = Path.of(System.getenv().getOrDefault("AUTONOMY_WORKSPACE", "notes"));
        this.verifyCmd = System.getenv().getOrDefault("VERIFY_CMD", "true");
    }

    /**
     * 跑一次，跑到自己认为可以停为止。返回值是停下来的原因，给调用方（lead）看。
     *
     * 同步的：调用方在 autonomy_start 里等它。因为循环里每一步都要读写同一张任务板，
     * 让它跟主循环并发跑，任务板会变成两个线程抢的东西——那是另一篇的事。
     */
    public String start(String goal) {
        if (running) {
            return "(error: 上一轮自主驱动还没停，先 autonomy_status 看看它在干什么)";
        }
        this.goal = goal == null || goal.isBlank() ? "（没给目标，按任务板自己找活）" : goal.strip();
        running = true;
        stopRequested = false;
        round = 0;
        stall = 0;
        tokens = 0;
        completed = 0;
        bounces = 0;
        stopReason = "";
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new RuntimeException("建不了工作目录 " + workspace, e);
        }

        System.out.println("   [autonomy] 目标：" + this.goal);
        System.out.println("   [autonomy] 上限：回合 " + MAX_ROUNDS + " / token " + MAX_TOKENS
                + " / 连续 " + STALL_ROUNDS + " 轮无进展就停"
                + "（AUTONOMY_MODE=" + (NAIVE ? "naive" : "checked") + "，工作目录 "
                + workspace.toAbsolutePath() + "）");

        while (true) {
            if (stopRequested) {
                stopReason = "stop";
                System.out.println("   [autonomy] 手动停，停在 R" + round);
                break;
            }
            if (round >= MAX_ROUNDS) {
                stopReason = "rounds";
                System.out.println("   [autonomy] 回合上限 MAX_ROUNDS=" + MAX_ROUNDS
                        + " 用完，停在 R" + round);
                break;
            }
            if (tokens >= MAX_TOKENS) {
                stopReason = "tokens";
                System.out.println("   [autonomy] token 预算先用完：累计估算 " + tokens
                        + " 越过 MAX_TOKENS=" + MAX_TOKENS + "，停在 R" + round);
                break;
            }

            // 停滞判据放在「这一轮开始之前」看：streak 已经够了，就不再有这一轮。
            // 所以日志里说的 R7 是「第 7 轮没有开始」，不是「第 7 轮跑完才发现」。
            int next = round + 1;
            if (!NAIVE && stall >= STALL_ROUNDS) {
                stopReason = "stall";
                System.out.println("   [autonomy] 连续 " + STALL_ROUNDS + " 轮无进展，停在 R" + next
                        + "（第 " + next + " 轮没有开始）");
                break;
            }
            round = next;

            TaskStore.Task picked = pick();
            if (picked == null) {
                stopReason = "empty";
                System.out.println("   [autonomy] 没有可开工的任务，停在 R" + round);
                break;
            }
            oneRound(picked);
        }

        running = false;
        String summary = "自主驱动停了：" + why() + "。跑了 " + round + " 轮，估算 " + tokens
                + " tokens，完成 " + completed + " 条，自检打回 " + bounces + " 次。"
                + tasks.report();
        System.out.println("   [autonomy] 停：" + stopReason + "｜" + summary);
        return summary;
    }

    /** 挑一条：ready（PENDING 且前置全完成）加上「上一轮被自检打回的、owner 是我的」 */
    private TaskStore.Task pick() {
        List<TaskStore.Task> pool = new ArrayList<>(tasks.ready());
        for (TaskStore.Task t : tasks.all()) {
            if (t.status() == TaskStore.Status.IN_PROGRESS && OWNER.equals(t.owner())) {
                pool.add(t);
            }
        }
        pool.sort(BY_PRIORITY);
        StringBuilder order = new StringBuilder();
        for (TaskStore.Task t : pool) {
            if (order.length() > 0) {
                order.append(' ');
            }
            order.append(t.id()).append("(p").append(t.priority()).append(')');
        }
        TaskStore.Task picked = pool.isEmpty() ? null : pool.get(0);
        System.out.println("   [autonomy] R" + round + " 候选 " + pool.size() + " 条："
                + (pool.isEmpty() ? "（空）" : order.toString())
                + " → 挑 " + (picked == null ? "（没有）" : picked.id()));
        return picked;
    }

    /** 一轮：认领 → 干活 → 自检 → 记账。四步里任何一步的结论都同时写进日志和盘上 */
    private void oneRound(TaskStore.Task picked) {
        String id = picked.id();
        TaskStore.Status statusBefore = picked.status();
        String noteBefore = picked.note();
        Map<String, String> before = snapshot();

        // 认领。已经是我自己的重试任务，这一步只是把 owner 再写一遍（空串=不改，这里给的是实名）
        tasks.update(id, TaskStore.Status.IN_PROGRESS, OWNER);

        int tokensBefore = tokens;
        String conclusion = work(tasks.get(id));
        int spent = tokens - tokensBefore;
        System.out.println("   [autonomy] R" + round + " " + id + " 干活：worker 走完，本轮 "
                + spent + " tokens（累计 " + tokens + "）｜结论 " + clip(conclusion, 60));

        Verify v = verify(tasks.get(id));
        TaskStore.Task after;
        if (v.ok()) {
            after = tasks.update(id, TaskStore.Status.COMPLETED, OWNER, "自检通过（" + verifyCmd + "）");
            completed++;
            System.out.println("   [autonomy] R" + round + " " + id + " 自检：exit 0，通过（"
                    + verifyCmd + "）");
        } else {
            bounces++;
            String note = "自检失败（exit " + v.code + "）：" + v.reason();
            after = tasks.update(id, TaskStore.Status.IN_PROGRESS, OWNER, note);
            System.out.println("   [autonomy] R" + round + " " + id + " 自检：exit " + v.code
                    + "，失败（" + verifyCmd + "）");
            System.out.println("   [autonomy] R" + round + " " + id + " 打回 IN_PROGRESS：" + note);
        }

        Map<String, String> afterSnap = snapshot();
        int delta = diff(before, afterSnap);
        boolean statusMoved = after.status() != statusBefore;
        boolean noteMoved = !after.note().equals(noteBefore);
        boolean moved = statusMoved || noteMoved || delta != 0;

        if (moved) {
            stall = 0;
        } else {
            stall++;
        }

        System.out.println("   [autonomy] R" + round + " " + id + " 记账：状态 " + statusBefore
                + "→" + after.status() + (statusMoved ? "" : " 未变")
                + "，快照 " + (delta > 0 ? "+" + delta : delta == 0 ? "±0" : String.valueOf(delta))
                + "，note " + (noteMoved ? "变了" : "没变")
                + (moved ? " → 有进展" : " → 无进展（连续 " + stall + " 轮）"));
    }

    /**
     * 干活：一次独立会话。
     *
     * 它只知道三件事——目标、这一条任务、验收命令。看不到别的任务，看不到我在记账，
     * 也看不到上一轮为什么没过（除非那句失败原因写在任务的 note 上）。
     * 上下文隔离是这里有意义的前提：不然「这一轮干了什么」根本分不清是谁干的。
     */
    private String work(TaskStore.Task task) {
        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", workerSystem(task)));
        messages.add(message("user", "任务 " + task.id() + "（p" + task.priority() + "）："
                + task.subject()
                + (task.note().isBlank() ? "" : "\n上一次没过，原因写在任务上：" + task.note())
                + "\n做完用一句话说清你改了什么。"));

        ArrayNode tools = workerTools.schemas();
        for (int step = 1; step <= WORKER_STEPS; step++) {
            tokens += estimate(messages);
            JsonNode response;
            try {
                response = client.createChatCompletion(messages, tools);
            } catch (ApiException e) {
                // worker 挂了不该带走驱动：这一轮记成「没结论」，自检会把它打回去
                System.out.println("   [autonomy] worker 失败：" + e.failure() + " HTTP " + e.status());
                return "（worker 调用失败：" + e.failure() + "）";
            }
            JsonNode msg = response.path("choices").path(0).path("message");
            messages.add(msg);

            JsonNode calls = msg.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                return msg.path("content").asText("(worker 没有输出)");
            }
            for (JsonNode call : calls) {
                String callId = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                JsonNode args = parseArgs(call.path("function").path("arguments"));
                System.out.println("   [autonomy] R" + round + " " + task.id() + " worker → "
                        + name + "(" + args + ")");

                // 门禁不跳过。自主不等于免检，写越界照样被拒
                String blocked = hooks.trigger(Event.preTool(name, args));
                if (blocked != null) {
                    messages.add(toolMessage(callId, blocked));
                    continue;
                }
                String output;
                try {
                    output = workerTools.dispatch(name, args);
                } catch (RuntimeException e) {
                    output = "(error: " + e.getMessage() + ")";
                }
                hooks.trigger(Event.postTool(name, args, output));
                messages.add(toolMessage(callId, output));
            }
        }
        System.out.println("   [autonomy] R" + round + " " + task.id() + " worker 走满 "
                + WORKER_STEPS + " 步还没结论，收回");
        return "(worker 走满 " + WORKER_STEPS + " 步没有给结论)";
    }

    private String workerSystem(TaskStore.Task task) {
        return """
                You are the executor for one task inside an autonomous run. You get exactly one task,
                not the whole goal, and you do not see the driver's log or the other tasks.

                Goal: %s
                This task: %s p%d %s
                Working directory: %s

                Do the task with the tools, then reply with ONE short sentence about what you changed.
                Do not report on tasks you were not given. Do not ask questions — nobody is reading this
                session live; the only thing that comes back is your last sentence.

                When you are done, the driver runs this command in the working directory to check you:
                  %s
                If it fails, the task goes back to IN_PROGRESS and the failure is written on it.
                """.formatted(goal, task.id(), task.priority(), task.subject(),
                workspace.toAbsolutePath(), verifyCmd);
    }

    /** 自检：把任务 id 当环境变量传进去，一个命令能验所有任务 */
    private Verify verify(TaskStore.Task task) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", verifyCmd);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("AUTONOMY_TASK_ID", task.id());
            pb.environment().put("AUTONOMY_TASK_SUBJECT", task.subject());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Verify(false, 124, "自检命令 60s 没结束");
            }
            int code = p.exitValue();
            String text = out.strip();
            // 失败时把命令自己的输出带回去；它不输出就记一句，别留空 note
            return new Verify(code == 0, code,
                    text.isEmpty() ? (code == 0 ? "" : "命令没有输出") : clip(text, 120));
        } catch (Exception e) {
            return new Verify(false, 127, "自检起不来：" + e.getMessage());
        }
    }

    /**
     * 工作目录快照：路径 → 大小 + mtime。
     *
     * 只比文件名不够——worker 完全可以把同一个文件重写一遍，什么都没改也叫「动了」。
     * 加上大小，改一个字的概率就低得多；真要精确到内容，得存哈希，
     * 但那是每一轮都要重读一遍所有文件，这里不值。
     */
    private Map<String, String> snapshot() {
        Map<String, String> out = new HashMap<>();
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    out.put(root.relativize(p).toString(),
                            Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    private static int diff(Map<String, String> a, Map<String, String> b) {
        int n = 0;
        for (Map.Entry<String, String> e : b.entrySet()) {
            if (!e.getValue().equals(a.get(e.getKey()))) {
                n++;
            }
        }
        for (String k : a.keySet()) {
            if (!b.containsKey(k)) {
                n--;
            }
        }
        return n;
    }

    public String status() {
        int left = Math.max(0, MAX_TOKENS - tokens);
        return "autonomy " + (running ? "跑着" : "停着")
                + "｜目标「" + clip(goal, 30) + "」"
                + "｜轮次 R" + round + "/" + MAX_ROUNDS
                + "｜tokens " + tokens + "/" + MAX_TOKENS + "（还剩 " + left + "）"
                + "｜连续无进展 " + stall + "/" + STALL_ROUNDS + " 轮"
                + "｜完成 " + completed + " 条，打回 " + bounces + " 次"
                + "｜停因 " + (stopReason.isBlank() ? "（还没停过）" : stopReason + "：" + why());
    }

    /** 置一个停字。只在轮次之间生效——一轮跑到一半不会被打断，任务板不会留下半条记账 */
    public String stop() {
        if (!running) {
            return "驱动已经停了（" + (stopReason.isBlank() ? "没跑过" : stopReason + "：" + why())
                    + "），stop 只对正在跑的轮次有意义";
        }
        stopRequested = true;
        return "已置停：当前这一轮走完就停，不会再挑下一条";
    }

    private String why() {
        return switch (stopReason) {
            case "stall" -> "连续 " + STALL_ROUNDS + " 轮没有进展，第 " + (round + 1) + " 轮没有开始";
            case "rounds" -> "回合预算 MAX_ROUNDS=" + MAX_ROUNDS + " 用完";
            case "tokens" -> "token 预算 MAX_TOKENS=" + MAX_TOKENS + " 先用完（估算 " + tokens + "）";
            case "empty" -> "任务板上没有可开工的任务了";
            case "stop" -> "被 autonomy_stop 叫停";
            default -> "跑完了";
        };
    }

    /**
     * token 估算：中日韩字符按 1 token，其余按 4 字符 1 token。
     *
     * 这不是账单，是个上限的近似——它只用来回答「还能不能再跑一轮」。
     * 估低了会多花一点钱，估高了一轮白等；我在这一篇选估高一点。
     */
    static int estimate(ArrayNode messages) {
        int cjk = 0;
        int other = 0;
        for (JsonNode m : messages) {
            String c = m.path("content").asText("");
            for (int i = 0; i < c.length(); i++) {
                if (c.charAt(i) > 0x2E80) {
                    cjk++;
                } else {
                    other++;
                }
            }
        }
        return cjk + (other + 3) / 4;
    }

    private static int idNum(String id) {
        try {
            return Integer.parseInt(id.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private record Verify(boolean ok, int code, String reason) {
    }

    private ObjectNode message(String role, String content) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        return n;
    }

    private ObjectNode toolMessage(String id, String content) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", "tool");
        n.put("tool_call_id", id);
        n.put("content", content);
        return n;
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

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static int intEnv(String key, int def) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
