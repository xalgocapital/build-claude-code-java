package dev.lcc.s14;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * 调度器：一条守护线程，到点把任务喂给模型。
 *
 * 和 s13 的差别就是「谁踢一脚」。后台任务要模型 poll 才被发现结束；
 * 这里没人问，到点自己跑。跑的方式是新起一个会话：独立 messages、独立 system，
 * 和主会话不共享任何上下文——定时任务发生在半夜，主会话可能根本不在跑。
 *
 * 三个决定：
 *
 *   1. 时间用「调度时钟」。CRON_SCALE_MS 把真实的一分钟折算成多少毫秒（默认 60000，
 *      即不缩放）。测试设成 200，`* * * * *` 就变成每 200ms 触发一次——不然验证一次
 *      调度要等真的一分钟。缩放只影响时钟流逝速度，表达式解析和日历推算一行不改。
 *      这是给实验用的，生产不要开。
 *
 *   2. 到点触发一次且只触发一次。跑完把 nextRun 推进到下一次，同一分钟内不会因为
 *      tick 密集而重复触发——next() 返回的是严格晚于触发时刻的下一个点。
 *
 *   3. 错过不补跑。重启时 nextRun 已经落在很远的过去（机器关了一夜），从那到现在的
 *      每一个触发点都错过了。只按最新的一次跑，差额记进 skipped。
 *      补跑的反例是 CRON_MODE=naive：把错过的每个点都跑一遍，重启瞬间打出 N 个会话。
 */
public final class CronScheduler {
    /** 醒一次的间隔，单位是「调度时钟」的毫秒。默认 500，缩放开启时按比例折算成真实毫秒 */
    static final long TICK_MS = Integer.parseInt(
            System.getenv().getOrDefault("CRON_TICK_MS", "500"));
    /** 真实一分钟折算成多少毫秒。60000 = 不缩放；给实验用的，生产不要开 */
    static final long SCALE_MS = Long.parseLong(
            System.getenv().getOrDefault("CRON_SCALE_MS", "60000"));
    /** 反例开关：把错过的周期全部补跑 */
    static final boolean NAIVE = "naive".equals(System.getenv().getOrDefault("CRON_MODE", "catchup"));

    /** 定时会话的身份段。假端点靠这一句区分主会话和定时会话 */
    static final String CRON_IDENTITY =
            "[cron] You are a scheduled task runner. A cron trigger fired and this prompt is the "
                    + "whole task. Nobody is watching: do the task, then answer in plain text. "
                    + "You have no tools and no prior conversation.";

    private final OpenAICompatClient client;
    private final CronStore store;
    private final Path dir;
    private final long realStart = System.currentTimeMillis();
    /** 每真实 1ms，调度时钟走多少 ms。SCALE=200 时是 300：1 秒真时 = 5 分钟调度时 */
    private final double factor = 60000.0 / SCALE_MS;
    private int fired;
    private int skippedTotal;
    private int failed;

    public CronScheduler(OpenAICompatClient client, CronStore store, Path dir) {
        this.client = client;
        this.store = store;
        this.dir = dir;
    }

    /** 调度时钟的当前时刻。没开缩放时它就是墙钟；缩放开启时日历会跑得比墙钟快 */
    public LocalDateTime now() {
        long realNow = System.currentTimeMillis();
        long virtualMs = realStart + (long) ((realNow - realStart) * factor);
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(virtualMs),
                java.time.ZoneId.systemDefault());
    }

    /** 真实毫秒换算：调度时钟走 tickMs，真实世界要等多久 */
    private long realSleepMs() {
        return Math.max(1, (long) (TICK_MS / factor));
    }

    public void start() {
        Thread t = new Thread(this::loop, "cron-scheduler");
        t.setDaemon(true);
        t.start();
        System.out.println("   [cron] 调度器启动：每 " + TICK_MS + "ms（调度时钟）醒一次，tick≈"
                + realSleepMs() + "ms 真时，模式 " + (NAIVE ? "naive（补跑）" : "catchup（不补跑）"));
    }

    private void loop() {
        while (true) {
            // parkNanos 而不是 sleep：缩放开启时真实间隔只有 1-2ms，sleep 的毫秒粒度
            // 会把 tick 拖过一整个调度分钟，凭空制造「错过」
            java.util.concurrent.locks.LockSupport.parkNanos(realSleepMs() * 1_000_000);
            try {
                tick();
            } catch (RuntimeException e) {
                // 调度器死了没人知道。任何一轮的异常都只记一笔，下个 tick 接着来
                System.out.println("   [cron] tick 异常：" + e);
            }
        }
    }

    /**
     * 一次醒来干的事：找出 nextRun 已到的任务，逐条处理。
     *
     * 补跑判定在这里：从 nextRun 到现在一共该触发几次（due）。只跑最新的一次，
     * 剩下 due-1 次记进 skipped；naive 模式反过来，due 次全部跑掉。
     */
    private void tick() {
        LocalDateTime now = now();
        for (CronStore.Job job : store.all()) {
            if (!job.enabled || job.nextRun == null) {
                continue;
            }
            LocalDateTime due = CronStore.parseTs(job.nextRun);
            if (due.isAfter(now)) {
                continue;
            }
            int missed = countDue(job, due, now);   // 该触发而没触发的次数（含这次要跑的）
            if (NAIVE) {
                // 反例：错过的每个点都补一个会话。重启瞬间就是 N 连发
                for (int i = 0; i < missed; i++) {
                    run(job, due.plusMinutes(i), 0);
                }
                // run() 会把 nextRun 推到下一个点，最后一次可能还落在过去，
                // 显式越过 now，不然下一个 tick 又补一轮
                job.nextRun = CronStore.formatTs(job.cron.next(now));
            } else {
                run(job, now, missed - 1);
                job.skipped += missed - 1;
                skippedTotal += missed - 1;
            }
            store.save();
        }
    }

    /** [from, now] 之间共有几个触发点。now 本身撞上也算 */
    private static int countDue(CronStore.Job job, LocalDateTime from, LocalDateTime now) {
        int n = 0;
        LocalDateTime t = from;
        while (!t.isAfter(now)) {
            n++;
            t = job.cron.next(t);
        }
        return n;
    }

    /**
     * 新起一个会话跑一次。独立 messages：一条 system（cron 身份）+ 一条 user（任务正文）。
     * 主会话此刻可能在跑也可能没在跑，两边互不知道对方的存在。
     */
    private void run(CronStore.Job job, LocalDateTime triggerAt, int missedBefore) {
        fired++;
        String head = "[cron] " + job.id + " 触发"
                + (missedBefore > 0 ? "（错过 " + missedBefore + " 次，不补跑）" : "")
                + "：" + job.expr;
        System.out.println(head);
        job.lastRun = CronStore.formatTs(triggerAt);
        job.runs++;
        job.nextRun = CronStore.formatTs(job.cron.next(triggerAt));

        ObjectMapper m = client.mapper();
        ArrayNode messages = m.createArrayNode();
        ObjectNode sys = m.createObjectNode();
        sys.put("role", "system");
        sys.put("content", CRON_IDENTITY);
        messages.add(sys);
        ObjectNode user = m.createObjectNode();
        user.put("role", "user");
        user.put("content", job.prompt);
        messages.add(user);

        String result;
        try {
            JsonNode resp = client.createChatCompletion(messages, null);
            result = resp.path("choices").path(0).path("message").path("content").asText("");
        } catch (RuntimeException e) {
            failed++;
            result = "(run failed: " + e.getMessage() + ")";
        }
        appendLog(job, triggerAt, result);
    }

    /** 结果落盘：.cron/<id>.log。主会话不在跑，模型的话只能留在这里等人来看 */
    private void appendLog(CronStore.Job job, LocalDateTime triggerAt, String result) {
        String line = "=== " + CronStore.formatTs(triggerAt) + " run#" + job.runs + " ===\n"
                + result.strip() + "\n";
        try {
            Files.writeString(dir.resolve(job.id + ".log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("   [cron] 日志写盘失败：" + e.getMessage());
        }
    }

    public String report() {
        return "[cron] 任务 " + store.all().size() + " 条，触发 " + fired
                + " 次，错过不补 " + skippedTotal + " 次，失败 " + failed + " 次";
    }
}
