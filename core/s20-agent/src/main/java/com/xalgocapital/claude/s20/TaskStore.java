package com.xalgocapital.claude.s20;

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
 */
public final class TaskStore {
    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED, ORPHANED
    }

    public record Task(String id, String subject, Status status, List<String> blockedBy,
                       String owner, String createdAt, String updatedAt, String note) {

        Task with(Status s, String newOwner, String newNote) {
            return new Task(id, subject, s, blockedBy, newOwner == null ? owner : newOwner,
                    createdAt, now(), newNote == null ? note : newNote);
        }

        boolean done() {
            return status == Status.COMPLETED;
        }
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

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
        for (String dep : blockedBy) {
            if (!tasks.containsKey(dep)) {
                throw new IllegalArgumentException("blockedBy 里的 " + dep + " 不存在");
            }
        }
        String id = "T" + (++seq);
        Task t = new Task(id, subject, Status.PENDING, List.copyOf(blockedBy), "",
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
        Task t = tasks.get(id);
        if (t == null) {
            throw new IllegalArgumentException("没有 " + id);
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
        Task updated = t.with(status, owner, null);
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
        Task candidate = new Task(t.id(), t.subject(), t.status(), List.copyOf(blockedBy),
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
                sb.append("  ").append(t.id()).append(' ').append(t.subject()).append('\n');
            }
        }
        List<String> blocked = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (t.status() == Status.PENDING && !ready.contains(t)) {
                blocked.add("  " + t.id() + " " + t.subject() + " ← blocked by "
                        + String.join(",", t.blockedBy()));
            }
        }
        if (!blocked.isEmpty()) {
            sb.append("blocked (do NOT start these yet):\n").append(String.join("\n", blocked)).append('\n');
        }
        for (Task t : tasks.values()) {
            if (t.status() == Status.IN_PROGRESS) {
                sb.append("in progress: ").append(t.id()).append(' ').append(t.subject()).append('\n');
            }
        }
        for (Task t : tasks.values()) {
            if (t.status() == Status.ORPHANED) {
                sb.append("orphaned from last session: ").append(t.id()).append(' ').append(t.subject())
                        .append("（决定继续还是重开）\n");
            }
        }
        long done = tasks.values().stream().filter(Task::done).count();
        if (done > 0) {
            sb.append("completed: ").append(done).append('\n');
        }
        return sb.toString().strip();
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
