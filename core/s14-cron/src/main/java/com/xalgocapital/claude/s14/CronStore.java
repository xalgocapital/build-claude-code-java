package com.xalgocapital.claude.s14;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时任务表：.cron/jobs.json。
 *
 * 一条 job 的全部状态：id / expr / prompt / enabled / lastRun / nextRun / runs / skipped。
 * 和 s12 的任务一样，状态在磁盘上不在内存里——进程重启后 CronStore.open() 原样读回，
 * 调度器接着排班。这正好是这一章的重点实验：重启时 nextRun 落在很远的过去怎么办。
 *
 * 时间字段存的是「调度时钟」的时刻。CRON_SCALE_MS=60000（默认）时它就是墙钟；
 * 开了缩放它跑得比墙钟快，README 里有解释。存 ISO 字符串是为了人能直接读。
 */
public final class CronStore {
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ObjectMapper mapper;
    private final Path file;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private int seq;
    /** 调度时钟。建任务时算第一个 nextRun 用——「现在几点」只有调度器知道 */
    private java.util.function.Supplier<LocalDateTime> clock;

    private CronStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    /** 调度器启动后把时钟接进来。缩放开启时它不是墙钟 */
    public void attachClock(java.util.function.Supplier<LocalDateTime> clock) {
        this.clock = clock;
    }

    /** 一条定时任务的全部状态。cron 对象解析一次缓存住，调度时不再重解析 */
    public static final class Job {
        public final String id;
        public final String expr;
        public final String prompt;
        public boolean enabled = true;
        public String lastRun;      // ISO，null = 还没跑过
        public String nextRun;      // ISO，调度器唯一看的字段
        public int runs;
        public int skipped;
        Cron cron;

        Job(String id, String expr, String prompt, Cron cron) {
            this.id = id;
            this.expr = expr;
            this.prompt = prompt;
            this.cron = cron;
        }
    }

    /** 启动时读回。文件不存在就是空表；坏一条不连坐整个文件 */
    public static CronStore open(ObjectMapper mapper) {
        Path file = Path.of(System.getenv().getOrDefault("CRON_DIR", ".cron")).resolve("jobs.json");
        CronStore store = new CronStore(mapper, file);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (Files.exists(file)) {
            try {
                JsonNode root = mapper.readTree(file.toFile());
                int maxSeq = 0;
                for (JsonNode n : root.path("jobs")) {
                    try {
                        Job job = store.fromNode(n);
                        store.jobs.put(job.id, job);
                        if (job.id.startsWith("cron_")) {
                            maxSeq = Math.max(maxSeq, Integer.parseInt(job.id.substring(5)));
                        }
                    } catch (RuntimeException bad) {
                        System.out.println("   [cron] 跳过一条坏记录：" + bad.getMessage());
                    }
                }
                store.seq = maxSeq;
                System.out.println("   [cron] 从磁盘读回 " + store.jobs.size() + " 条定时任务");
            } catch (IOException e) {
                System.out.println("   [cron] jobs.json 读不出来，当空表用：" + e.getMessage());
            }
        }
        return store;
    }

    private Job fromNode(JsonNode n) {
        Cron cron = Cron.parse(n.path("expr").asText());
        Job job = new Job(n.path("id").asText(), n.path("expr").asText(),
                n.path("prompt").asText(), cron);
        job.enabled = n.path("enabled").asBoolean(true);
        job.lastRun = n.path("lastRun").isTextual() ? n.path("lastRun").asText() : null;
        job.nextRun = n.path("nextRun").isTextual() ? n.path("nextRun").asText() : null;
        job.runs = n.path("runs").asInt(0);
        job.skipped = n.path("skipped").asInt(0);
        return job;
    }

    public synchronized Job create(String expr, String prompt) {
        Cron cron;
        try {
            cron = Cron.parse(expr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("(error: " + e.getMessage() + ")");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("(error: prompt 不能为空——到点之后模型要干什么，得说清楚)");
        }
        Job job = new Job("cron_" + (++seq), expr, prompt, cron);
        if (clock != null) {
            job.nextRun = formatTs(cron.next(clock.get()));
        }
        jobs.put(job.id, job);
        save();
        return job;
    }

    public synchronized Job delete(String id) {
        Job gone = jobs.remove(id);
        if (gone == null) {
            throw new IllegalArgumentException("(error: 没有 " + id + " 这条任务)");
        }
        save();
        return gone;
    }

    public synchronized java.util.List<Job> all() {
        return new java.util.ArrayList<>(jobs.values());
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    /** cron_list 的一行。next= 是模型排班时唯一需要看的字段 */
    public synchronized String listText() {
        if (jobs.isEmpty()) {
            return "(no cron jobs)";
        }
        StringBuilder sb = new StringBuilder();
        for (Job j : jobs.values()) {
            sb.append(j.id).append("  ").append(j.expr)
                    .append("  next=").append(j.nextRun == null ? "?" : j.nextRun.replace('T', ' '))
                    .append("  runs=").append(j.runs)
                    .append("  skipped=").append(j.skipped)
                    .append(j.enabled ? "" : "  [disabled]")
                    .append('\n').append("    prompt: ").append(j.prompt).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public synchronized void save() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = mapper.createArrayNode();
        for (Job j : jobs.values()) {
            ObjectNode n = mapper.createObjectNode();
            n.put("id", j.id);
            n.put("expr", j.expr);
            n.put("prompt", j.prompt);
            n.put("enabled", j.enabled);
            if (j.lastRun == null) {
                n.putNull("lastRun");
            } else {
                n.put("lastRun", j.lastRun);
            }
            if (j.nextRun == null) {
                n.putNull("nextRun");
            } else {
                n.put("nextRun", j.nextRun);
            }
            n.put("runs", j.runs);
            n.put("skipped", j.skipped);
            arr.add(n);
        }
        root.set("jobs", arr);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            System.out.println("   [cron] jobs.json 写盘失败：" + e.getMessage());
        }
    }

    public static LocalDateTime parseTs(String iso) {
        return LocalDateTime.parse(iso, TS);
    }

    public static String formatTs(LocalDateTime t) {
        return t.format(TS);
    }
}
