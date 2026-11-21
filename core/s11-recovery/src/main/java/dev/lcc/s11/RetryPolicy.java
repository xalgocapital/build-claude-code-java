package dev.lcc.s11;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试的节奏。
 *
 * 三条规矩：
 *   1. 指数增长（base × 2^(n-1)），上限 cap
 *   2. 等值抖动：实际等待 ∈ [delay/2, delay]，不是 delay 本身
 *   3. 服务端给了 Retry-After 就听它的，别自作聪明
 *
 * 第 2 条是重点。指数退避解决的是「别把服务端打死」，抖动解决的是「别把服务端同时打死」：
 * 十个客户端在同一毫秒被 429，退避都是 1s，那就在同一毫秒再来一次。
 *
 * 为什么用等值抖动而不是「满抖动」（random(0, delay)）：满抖动会让间隔不可预测，
 * 「第 3 次等了多久」这种断言写不出来。留一半固定值，既错峰又单调。
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final long baseMs;
    private final long capMs;

    private final List<String> trace = new ArrayList<>();
    private int failures;
    private long totalSleptMs;
    private long maxSleptMs;

    public RetryPolicy() {
        this.maxAttempts = Integer.parseInt(System.getenv().getOrDefault("RETRY_MAX", "4"));
        this.baseMs = Long.parseLong(System.getenv().getOrDefault("RETRY_BASE_MS", "500"));
        this.capMs = Long.parseLong(System.getenv().getOrDefault("RETRY_CAP_MS", "8000"));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 第 attempt 次失败之后该等多久（attempt 从 1 开始）。
     *
     * @param retryAfterMs 服务端 Retry-After 换算出的毫秒，没有就传 0
     */
    public long delayFor(int attempt, long retryAfterMs) {
        if (retryAfterMs > 0) {
            return retryAfterMs;
        }
        long raw = baseMs * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(raw, capMs);
        long half = capped / 2;
        return half + ThreadLocalRandom.current().nextLong(capped - half + 1);
    }

    /** 睡一次，记账。测试里把 RETRY_BASE_MS 调小就能跑快 */
    public void sleep(long ms) {
        failures++;
        totalSleptMs += ms;
        maxSleptMs = Math.max(maxSleptMs, ms);
        trace.add(ms + "ms");
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void note(String what) {
        trace.add(what);
    }

    public void report() {
        if (failures == 0) {
            System.out.println("   [retry] 本次没有失败请求");
            return;
        }
        System.out.println("   [retry] 共重试 " + failures + " 次，累计等待 " + totalSleptMs
                + "ms，最长一次 " + maxSleptMs + "ms");
        System.out.println("   [retry] 退避序列：" + String.join(" → ", trace));
    }

    /** 给假端点断言用：把退避序列写进一行日志 */
    public List<String> trace() {
        return trace;
    }

    public long totalSleptMs() {
        return totalSleptMs;
    }

    /** Retry-After 头：既可以是秒数，也可以是 HTTP 日期。这里只认秒数，另一个分支留给真实实现 */
    public static long retryAfterMillis(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.strip())).toMillis();
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
