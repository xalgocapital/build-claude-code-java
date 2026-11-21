package com.xalgocapital.claude.s13;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台任务：把「跑很久的命令」从循环里摘出去。
 *
 * s02 的 bash 是同步的：命令跑 3 分钟，readLine 就阻塞 3 分钟，模型这 3 分钟什么都做不了。
 * 这里做三件事，缺一件都不成立：
 *
 *   1. 进程脱离循环。输出直接重定向到 .bg/&lt;id&gt;.out，循环不等它。
 *   2. 输出按增量读。每个任务记一个 offset，读完推进。回全文是这一篇最贵的错——
 *      模型会把同一段读五遍，上下文白烧，而它自己不知道重复了。
 *   3. 结束由 poll 发现，通知走 messages 不走 system。任务完成是典型的「临时事件」，
 *      塞进 system 等于每完成一个任务就把 s10 攒的前缀作废一次。
 *
 * 元信息写在 .bg/&lt;id&gt;.json：命令、pid、开始时间、超时、状态、exitCode。
 * exitCode 单独记一份不是给模型看的，是给下一个进程看的——进程没了，文件还在。
 */
public final class BackgroundTasks {
    /** bash_output 一次最多回这么多行；不设上限，一个 `yes` 就能把上下文撑爆 */
    static final int READ_TAIL_LINES = 40;
    /** 完成通知里带的末尾行数。整段输出塞进通知，等于把增量读白做了 */
    static final int NOTICE_TAIL_LINES = 20;
    /** 默认超时。真跑长任务的人应该显式传更大的值 */
    static final int DEFAULT_TIMEOUT_SEC = 600;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper;
    private final Path dir;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private int seq;
    private int started;
    private int finished;
    private int timedOut;
    private int kills;
    private int reads;
    private long charsReturned;
    private int linesDropped;

    public BackgroundTasks(ObjectMapper mapper) {
        this.mapper = mapper;
        this.dir = Path.of(System.getenv().getOrDefault("BG_DIR", ".bg"));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一个后台任务的全部状态。除了 Process，都能从 <id>.json 里读回来 */
    private static final class Job {
        final String id;
        final String command;
        final Process process;
        final int timeoutSec;
        final Path out;
        final long startedAt = System.currentTimeMillis();
        /** 已经交给模型的字节数。这个字段是「增量读」的全部实现 */
        long offset;
        String status = "running";
        Integer exitCode;
        /** poll 只报一次结束，报过就置位 */
        boolean reported;

        Job(String id, String command, Process process, int timeoutSec, Path out) {
            this.id = id;
            this.command = command;
            this.process = process;
            this.timeoutSec = timeoutSec;
            this.out = out;
        }

        String state() {
            return "exited".equals(status) || "timeout".equals(status) || "killed".equals(status)
                    ? status + " " + exitCode
                    : "running";
        }

        boolean alive() {
            return process.isAlive() && "running".equals(status);
        }
    }

    /** poll 的返回值：这一轮新结束的任务 */
    public record Finished(String id, int exitCode, String status, String tail) {}

    // ---------- 三个工具的实现 ----------

    public String start(String command, int timeoutSec) {
        if (command == null || command.isBlank()) {
            return "(error: empty command)";
        }
        String id = "bg_" + (++seq);
        Path out = dir.resolve(id + ".out");
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);   // stderr 并进来，错误和正常输出才排得上先后
            pb.redirectOutput(out.toFile());
            p = pb.start();
        } catch (IOException e) {
            return "(error: " + e.getMessage() + ")";
        }
        Job job = new Job(id, command, p, timeoutSec > 0 ? timeoutSec : DEFAULT_TIMEOUT_SEC, out);
        jobs.put(id, job);
        started++;
        writeMeta(job);
        System.out.println("   [bg] " + id + " 启动 pid " + p.pid() + "，超时 " + job.timeoutSec + "s："
                + command);
        return id + " started (pid " + p.pid() + ", timeout " + job.timeoutSec + "s). "
                + "Read it with bash_output(" + id + "); this call returned immediately.";
    }

    /**
     * 只回上次之后的新内容。
     *
     * offset 推进到最后一个完整行的末尾，不是文件末尾：进程可能正好写到一半，
     * 切在半个汉字上会解出乱码，切在半行上下一轮就重复了半行。
     * 一行都还没写完就先不回，等它有完整的一行。
     */
    public String output(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return "(error: unknown task " + id + ")";
        }
        reads++;

        byte[] all;
        try {
            all = Files.readAllBytes(job.out);
        } catch (IOException e) {
            return "(error: " + e.getMessage() + ")";
        }
        if (job.offset > all.length) {
            job.offset = 0;   // 文件被换过，从头来，总比永远读不到好
        }

        String chunk = new String(all, (int) job.offset, (int) (all.length - job.offset),
                StandardCharsets.UTF_8);
        int cut = chunk.lastIndexOf('\n');
        if (cut < 0) {
            if (job.alive()) {
                return "[" + id + " running] 还没有完整的一行";
            }
            cut = chunk.length() - 1;
        }
        if (cut < 0) {
            return "[" + id + " " + job.state() + "] 没有新输出";
        }
        String fresh = chunk.substring(0, cut + 1);
        job.offset += fresh.getBytes(StandardCharsets.UTF_8).length;

        if (fresh.isBlank()) {
            return "[" + id + " " + job.state() + "] 没有新输出";
        }

        List<String> lines = fresh.stripTrailing().lines().toList();
        String body;
        if (lines.size() > READ_TAIL_LINES) {
            int dropped = lines.size() - READ_TAIL_LINES;
            linesDropped += dropped;
            body = "（本次新增 " + lines.size() + " 行，只回最后 " + READ_TAIL_LINES
                    + " 行；更早的 " + dropped + " 行已丢弃）\n"
                    + String.join("\n", lines.subList(dropped, lines.size()));
        } else {
            body = String.join("\n", lines);
        }
        charsReturned += body.length();
        return "[" + id + " " + job.state() + "]\n" + body;
    }

    public String kill(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return "(error: unknown task " + id + ")";
        }
        if (!job.process.isAlive()) {
            return id + " already finished (" + job.state() + ")";
        }
        destroyTree(job.process);
        job.exitCode = waitExit(job.process);
        job.status = "killed";
        job.reported = true;   // kill 的返回值里已经有结果，poll 不用再报一次
        kills++;
        writeMeta(job);
        System.out.println("   [bg] " + id + " 被 kill_task 结束（exit " + job.exitCode + "）");
        return "killed " + id + " (exit " + job.exitCode + "). 它不会再产出新输出了。";
    }

    // ---------- 每轮调一次 ----------

    /**
     * 发现这一轮新结束的任务。
     *
     * 只在循环的空隙里看：进程不会回调你，你不看它就永远不知道它结束了。
     * 超时也在这里判——放在启动的时候 sleep 等待，就又变回同步了。
     */
    public List<Finished> poll() {
        List<Finished> done = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (job.reported) {
                continue;
            }
            if (job.process.isAlive()) {
                long elapsed = (System.currentTimeMillis() - job.startedAt) / 1000;
                if (elapsed >= job.timeoutSec) {
                    destroyTree(job.process);
                    job.exitCode = waitExit(job.process);
                    job.status = "timeout";
                    job.reported = true;
                    timedOut++;
                    writeMeta(job);
                    System.out.println("   [bg] " + job.id + " 超过 " + job.timeoutSec
                            + "s 没结束，强制杀掉（exit " + job.exitCode + "）");
                    done.add(new Finished(job.id, job.exitCode, "timeout", tail(job, NOTICE_TAIL_LINES)));
                }
                continue;
            }
            job.exitCode = job.process.exitValue();
            job.status = "exited";
            job.reported = true;
            finished++;
            writeMeta(job);
            done.add(new Finished(job.id, job.exitCode, "exited", tail(job, NOTICE_TAIL_LINES)));
        }
        return done;
    }

    /** 完成通知的正文。走 messages，不走 system——理由见 AgentLoop */
    public String notice(Finished f) {
        String head = "timeout".equals(f.status())
                ? "[background] " + f.id() + " timed out and was killed (exit " + f.exitCode() + ")"
                : "[background] " + f.id() + " finished (exit " + f.exitCode() + ")";
        return f.tail().isBlank()
                ? head + "\n（没有输出）"
                : head + "\n最后 " + f.tail().lines().count() + " 行：\n" + f.tail();
    }

    public String report() {
        return "[bg] 启动 " + started + " 个，正常结束 " + finished + "，超时 " + timedOut
                + "，被杀 " + kills + "；bash_output " + reads + " 次共回 " + charsReturned
                + " 字符，截断丢弃 " + linesDropped + " 行";
    }

    // ---------- 内部 ----------

    /** 读末尾 n 行，不动 offset。offset 是「交给模型的进度」，tail 是「通知里的样本」 */
    private String tail(Job job, int n) {
        try {
            List<String> lines = Files.readAllLines(job.out, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - n);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 杀进程树，不只是一个 sh。
     *
     * `sh -c "sleep 30"` 里 sh 是父进程、sleep 是子进程。只 destroy 父进程的话，
     * sleep 还活着，输出文件也不会关闭，看起来像「杀不掉」。
     */
    private static void destroyTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static int waitExit(Process p) {
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                return -1;
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 每次状态变化都落一份。进程消失以后，这份文件是唯一的证据 */
    private void writeMeta(Job job) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", job.id);
        node.put("command", job.command);
        node.put("pid", job.process.pid());
        node.put("started_at", LocalDateTime.now().format(TS));
        node.put("started_ms", job.startedAt);
        node.put("timeout_sec", job.timeoutSec);
        node.put("status", job.status);
        if (job.exitCode == null) {
            node.putNull("exit_code");
        } else {
            node.put("exit_code", job.exitCode);
            node.put("finished_ms", System.currentTimeMillis());
        }
        node.put("offset", job.offset);
        node.put("output", job.out.toString());
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve(job.id + ".json").toFile(), node);
        } catch (IOException e) {
            System.out.println("   [bg] 元信息写入失败：" + e.getMessage());
        }
    }
}
