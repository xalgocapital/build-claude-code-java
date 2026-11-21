package com.xalgocapital.claude.s18;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 任务系统：s05 的待办清单落到磁盘上。
 *
 * 三处变化，每一处都对应 s05 的一个「够用就好」：
 *
 *   1. 待办在内存里 → 任务在 .tasks/*.json 里。会话结束、进程被杀、换台机器，都还在。
 *   2. 待办是一张平表 → 任务有 blockedBy。做不了的别做，别靠模型自己记顺序。
 *   3. 「做完」是模型说的 → 「做完」是文件里写的。摘要、压缩、重启都改不了它。
 *
 * 还有一处不那么显眼但很重要的：in_progress 的任务在下次启动时会被标成 orphaned。
 * 上一次会话没做完就断了，这件事必须留痕——否则模型会以为还在跑。
 *
 * s17 加了一个 priority（默认 0，越大越先做）。自主驱动每一轮要从可开工的任务里
 * 挑一条，没有优先级就只能按文件顺序挑——顺序是磁盘给的，不是人给的。
 */
public final class TaskStore {
    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED, ORPHANED
    }

    public record Task(String id, String subject, int priority, Status status, List<String> blockedBy,
                       String owner, String createdAt, String updatedAt, String note) {

        Task with(Status s, String newOwner, String newNote) {
            // s15：空串表示「不改 owner」。s12 那版是「空串 = 清空」，
            // 于是成员标一次 COMPLETED 就把认领记录抹掉了，认领等于没做
            String owner2 = (newOwner == null || newOwner.isBlank()) ? owner : newOwner;
            return new Task(id, subject, priority, s, blockedBy, owner2,
                    createdAt, now(), newNote == null ? note : newNote);
        }

        boolean done() {
            return status == Status.COMPLETED;
        }
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /**
     * 反例开关。naive = 不做认领检查，谁最后写谁赢。
     * 这是直觉写法：「owner 就是个标签，覆盖一下有什么关系」。
     * 结果是两条任务被两个人同时做，而 owner 记的是后来那个没干活的人。
     */
    private static final boolean NAIVE = "naive".equals(System.getenv().getOrDefault("TEAM_MODE", "checked"));

    private final Path dir;
    private final ObjectMapper mapper;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private int seq;

    private TaskStore(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    /** 启动时读盘。in_progress 一律降级成 orphaned —— 上一次没做完，这件事得留痕 */
    public static TaskStore open(ObjectMapper mapper) {
        Path dir = Path.of(System.getenv().getOrDefault("TASK_DIR", ".tasks"));
        TaskStore store = new TaskStore(dir, mapper);
        try {
            Files.createDirectories(dir);
            Path seqFile = dir.resolve("seq");
            if (Files.exists(seqFile)) {
                store.seq = Integer.parseInt(Files.readString(seqFile).strip());
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                    try {
                        JsonNode n = mapper.readTree(Files.readString(p));
                        Task t = new Task(n.path("id").asText(), n.path("subject").asText(),
                                n.path("priority").asInt(0),
                                Status.valueOf(n.path("status").asText("PENDING")),
                                texts(n.path("blockedBy")), n.path("owner").asText(""),
                                n.path("createdAt").asText(""), n.path("updatedAt").asText(""),
                                n.path("note").asText(""));
                        if (t.status() == Status.IN_PROGRESS) {
                            t = t.with(Status.ORPHANED, null, "上次会话到这里就断了");
                            store.save(t);
                        }
                        store.tasks.put(t.id(), t);
                    } catch (Exception e) {
                        System.out.println("   [task] 跳过坏文件 " + p.getFileName() + "：" + e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            throw new RuntimeException("打不开任务目录 " + dir, e);
        }
        return store;
    }

    /** 建任务。blockedBy 里的 id 必须存在，且不能形成环 */
    public Task create(String subject, List<String> blockedBy) {
        return create(subject, blockedBy, 0);
    }

    /** s17：带优先级建任务。priority 只是个整数，比的是谁先做，不是谁重要 */
    public Task create(String subject, List<String> blockedBy, int priority) {
        for (String dep : blockedBy) {
            if (!tasks.containsKey(dep)) {
                throw new IllegalArgumentException("blockedBy 里的 " + dep + " 不存在");
            }
        }
        String id = "T" + (++seq);
        Task t = new Task(id, subject, priority, Status.PENDING, List.copyOf(blockedBy), "",
                now(), now(), "");
        tasks.put(id, t);
        if (createsCycle(id)) {
            tasks.remove(id);
            seq--;
            throw new IllegalArgumentException("这个依赖会形成环：" + id + " ← " + String.join(",", blockedBy));
        }
        save(t);
        persistSeq();
        return t;
    }

    /**
     * 改状态。开一个新任务之前先看依赖：前置没完成就不许开。
     *
     * 这条检查放在这里，而不是写在提示词里让模型自己注意——
     * 提示词是建议，代码是约束。
     */
    public Task update(String id, Status status, String owner) {
        return update(id, status, owner, null);
    }

    /**
     * s17：多一个 note。自检没过的时候，打回的原因必须写在任务上——
     * 下一轮再挑到它是同一份文件，看见的必须是「上次为什么没过」，
     * 而不是驱动进程内存里那句已经随着上一轮消失的话。
     */
    public Task update(String id, Status status, String owner, String note) {
        Task t = tasks.get(id);
        if (t == null) {
            throw new IllegalArgumentException("没有 " + id);
        }
        // s15：认领冲突。两个成员看到的是同一份看板，先后脚都会来抢同一条——
        // 这一行是唯一能保证「最后只有一个 owner」的地方。
        // 放在依赖检查之前：抢不到的东西，没必要先告诉它前置做完了没有
        if (!NAIVE && !owner.isBlank() && !t.owner().isBlank() && !t.owner().equals(owner)) {
            throw new IllegalStateException(id + " 已经被 " + t.owner() + " 认领");
        }
        if (status == Status.IN_PROGRESS) {
            List<String> waiting = new ArrayList<>();
            for (String dep : t.blockedBy()) {
                Task d = tasks.get(dep);
                if (d == null || !d.done()) {
                    waiting.add(dep);
                }
            }
            if (!waiting.isEmpty()) {
                throw new IllegalStateException(id + " 还等着 " + String.join(",", waiting) + " 完成");
            }
        }
        Task updated = t.with(status, owner, note);
        tasks.put(id, updated);
        save(updated);
        return updated;
    }

    /**
     * s16：移交专用。绕过认领冲突检查——handoff 是原 owner 主动让，
     * 不是第二个人来抢，冲突检查在这里只会把合法的移交挡死。
     */
    public Task transfer(String id, String newOwner) {
        Task t = tasks.get(id);
        if (t == null) {
            throw new IllegalArgumentException("没有 " + id);
        }
        Task updated = t.with(t.status(), newOwner, null);
        tasks.put(id, updated);
        save(updated);
        return updated;
    }

    /**
     * 改依赖。依赖关系会变——需求一变，「谁等谁」就得重排。
     * 但重排必须查环，否则 A 等 B、B 等 A，两个都永远开不了工，而且没有任何报错。
     */
    public Task rewire(String id, List<String> blockedBy) {
        Task t = tasks.get(id);
        if (t == null) {
            throw new IllegalArgumentException("没有 " + id);
        }
        for (String dep : blockedBy) {
            if (!tasks.containsKey(dep)) {
                throw new IllegalArgumentException("blockedBy 里的 " + dep + " 不存在");
            }
        }
        Task candidate = new Task(t.id(), t.subject(), t.priority(), t.status(), List.copyOf(blockedBy),
                t.owner(), t.createdAt(), now(), t.note());
        tasks.put(id, candidate);
        if (createsCycle(id)) {
            tasks.put(id, t);
            throw new IllegalArgumentException("这个依赖会形成环：" + id + " ← " + String.join(",", blockedBy));
        }
        save(candidate);
        return candidate;
    }

    public List<Task> all() {
        return new ArrayList<>(tasks.values());
    }

    public Task get(String id) {
        return tasks.get(id);
    }

    /** 可开工的：pending 且前置全部完成 */
    public List<Task> ready() {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.status() != Status.PENDING) {
                continue;
            }
            boolean ok = true;
            for (String dep : t.blockedBy()) {
                Task d = tasks.get(dep);
                if (d == null || !d.done()) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 进 system 的那一段。
     *
     * 只把 ready 的列成「可以开工」，被挡住的单独列一行——模型看得见它们存在，
     * 但不会以为现在就该做。这个区分是整篇的重点：看得见 ≠ 该做。
     */
    public String board() {
        if (tasks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<Task> ready = ready();
        if (!ready.isEmpty()) {
            sb.append("ready to start:\n");
            for (Task t : ready) {
                sb.append("  ").append(line(t)).append('\n');
            }
        }
        List<String> blocked = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.status() == Status.PENDING && !ready.contains(t)) {
                blocked.add("  " + line(t) + " ← blocked by "
                        + String.join(",", t.blockedBy()));
            }
        }
        if (!blocked.isEmpty()) {
            sb.append("blocked (do NOT start these yet):\n").append(String.join("\n", blocked)).append('\n');
        }
        for (Task t : tasks.values()) {
            if (t.status() == Status.IN_PROGRESS) {
                // s15：写出 owner。成员看板是为了决定「我去做哪条」，
                // 不写认领人，它就会去抢一条已经有人在做的
                sb.append("in progress: ").append(line(t))
                        .append(t.owner().isBlank() ? "" : "（" + t.owner() + "）").append('\n');
            }
        }
        for (Task t : tasks.values()) {
            if (t.status() == Status.ORPHANED) {
                sb.append("orphaned from last session: ").append(line(t))
                        .append("（决定继续还是重开）\n");
            }
        }
        long done = tasks.values().stream().filter(Task::done).count();
        if (done > 0) {
            sb.append("completed: ").append(done).append('\n');
        }
        return sb.toString().strip();
    }

    /** 看板上一行：T2 p5 补一条发布说明。优先级写在 id 后面，一眼看得出先做谁 */
    private static String line(Task t) {
        return t.id() + " p" + t.priority() + " " + t.subject();
    }

    public String report() {
        long done = tasks.values().stream().filter(Task::done).count();
        long orphan = tasks.values().stream().filter(t -> t.status() == Status.ORPHANED).count();
        return "磁盘上 " + tasks.size() + " 个任务：完成 " + done + "，可开工 " + ready().size()
                + (orphan > 0 ? "，孤儿 " + orphan : "");
    }

    // ---------- 内部 ----------

    private boolean createsCycle(String start) {
        // 从 start 出发沿 blockedBy 走，能走回 start 就是环
        return walk(start, start, new ArrayList<>());
    }

    private boolean walk(String start, String cur, List<String> seen) {
        if (seen.contains(cur)) {
            return false;
        }
        seen.add(cur);
        Task t = tasks.get(cur);
        if (t == null) {
            return false;
        }
        for (String dep : t.blockedBy()) {
            if (dep.equals(start)) {
                return true;
            }
            if (walk(start, dep, seen)) {
                return true;
            }
        }
        return false;
    }

    private void save(Task t) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", t.id());
        n.put("subject", t.subject());
        n.put("priority", t.priority());
        n.put("status", t.status().name());
        ArrayNode deps = mapper.createArrayNode();
        t.blockedBy().forEach(deps::add);
        n.set("blockedBy", deps);
        n.put("owner", t.owner());
        n.put("createdAt", t.createdAt());
        n.put("updatedAt", t.updatedAt());
        n.put("note", t.note());
        try {
            Files.writeString(dir.resolve(t.id() + ".json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n));
        } catch (IOException e) {
            throw new RuntimeException("写任务文件失败 " + t.id(), e);
        }
    }

    private void persistSeq() {
        try {
            Files.writeString(dir.resolve("seq"), String.valueOf(seq));
        } catch (IOException ignored) {
        }
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
