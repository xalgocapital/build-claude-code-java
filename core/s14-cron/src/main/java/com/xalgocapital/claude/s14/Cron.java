package com.xalgocapital.claude.s14;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TreeSet;

/**
 * 手写 cron 表达式。不引库——解析本身就是这一章的一部分。
 *
 * 五段：分 时 日 月 周。每段支持四种写法：
 *   *      每个值
 *   a      单个值
 *   a-b    区间
 *   *&#47;n 或 a-b&#47;n   步进
 * 逗号可以把上面任意几种拼起来：0,15,30-45/5。
 *
 * 字段值域按惯例：分 0-59，时 0-23，日 1-31，月 1-12，周 0-7（0 和 7 都是周日）。
 * 「日」和「周」都有限制时取并集——这是 vixie cron 的老规矩，不是笔误。
 *
 * next(from) 返回严格晚于 from 的下一次触发时刻（秒截断为 0）。
 * 实现就是逐分钟往后走：一分钟一步，最坏走到年 + 4（2 月 29 日要等四年），
 * 200 万次布尔判断，微秒级，不值得为它写日历推算。
 */
public final class Cron {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String expr;
    /** 每段的允许值集合。TreeSet 是为了报错时能打印出可读的值域 */
    private final TreeSet<Integer> minute = new TreeSet<>();
    private final TreeSet<Integer> hour = new TreeSet<>();
    private final TreeSet<Integer> dayOfMonth = new TreeSet<>();
    private final TreeSet<Integer> month = new TreeSet<>();
    private final TreeSet<Integer> dayOfWeek = new TreeSet<>();
    /** 「日」段是不是写了 * 。日和周的并集语义只有两边都受限时才生效 */
    private final boolean domWildcard;
    private final boolean dowWildcard;

    private Cron(String expr, TreeSet<Integer>[] sets, boolean domWildcard, boolean dowWildcard) {
        this.expr = expr;
        this.minute.addAll(sets[0]);
        this.hour.addAll(sets[1]);
        this.dayOfMonth.addAll(sets[2]);
        this.month.addAll(sets[3]);
        this.dayOfWeek.addAll(sets[4]);
        this.domWildcard = domWildcard;
        this.dowWildcard = dowWildcard;
    }

    /** 解析失败抛 IllegalArgumentException，消息里带上是哪一段、为什么 */
    public static Cron parse(String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("cron 表达式要 5 段（分 时 日 月 周），收到 " + parts.length + " 段：" + expr);
        }
        TreeSet<Integer>[] sets = new TreeSet[5];
        boolean domWildcard = "*".equals(parts[2]);
        boolean dowWildcard = "*".equals(parts[4]);
        int[][] ranges = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};
        for (int i = 0; i < 5; i++) {
            sets[i] = parseField(parts[i], ranges[i][0], ranges[i][1],
                    new String[]{"分", "时", "日", "月", "周"}[i], i == 4);
        }
        return new Cron(expr, sets, domWildcard, dowWildcard);
    }

    /**
     * 一段 = 逗号分开的若干项，每项是 * | a | a-b | （*|a-b)/n。
     * 周字段把 7 折成 0（都是周日），这样 contains(0) 一个判断就够了。
     */
    private static TreeSet<Integer> parseField(String field, int min, int max, String label, boolean isDow) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String item : field.split(",")) {
            int step = 1;
            String body = item;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                body = item.substring(0, slash);
                step = Integer.parseInt(item.substring(slash + 1));
                if (step < 1) {
                    throw new IllegalArgumentException(label + "段步进必须 ≥ 1：" + item);
                }
            }
            int lo, hi;
            if ("*".equals(body)) {
                lo = min;
                hi = max;
            } else if (body.contains("-")) {
                String[] ab = body.split("-");
                lo = Integer.parseInt(ab[0]);
                hi = Integer.parseInt(ab[1]);
            } else {
                lo = Integer.parseInt(body);
                // a/n 与 a 等价（vixie cron 的约定），不做 a/n 到区间末尾的展开
                hi = slash >= 0 ? max : lo;
            }
            if (lo < min || hi > max || lo > hi) {
                throw new IllegalArgumentException(label + "段值域超出 [" + min + "-" + max + "]：" + item);
            }
            for (int v = lo; v <= hi; v += step) {
                out.add(isDow && v == 7 ? 0 : v);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(label + "段没有可用的值：" + field);
        }
        return out;
    }

    /** 严格晚于 from 的下一次触发。逐分钟走，上限四年 */
    public LocalDateTime next(LocalDateTime from) {
        LocalDateTime t = from.withSecond(0).withNano(0);
        for (int i = 0; i < 5 * 366 * 24 * 60; i++) {
            t = t.plusMinutes(1);
            if (matches(t)) {
                return t;
            }
        }
        throw new IllegalStateException("四年内没有触发点，表达式写死了？" + expr);
    }

    /** 日和周都受限时取并集；否则各自独立判断 */
    private boolean matches(LocalDateTime t) {
        if (!minute.contains(t.getMinute()) || !hour.contains(t.getHour())
                || !month.contains(t.getMonthValue())) {
            return false;
        }
        boolean domOk = domWildcard || dayOfMonth.contains(t.getDayOfMonth());
        int dow = t.getDayOfWeek().getValue() % 7;   // Java 周一=1..周日=7；0=周日
        boolean dowOk = dowWildcard || dayOfWeek.contains(dow);
        if (domWildcard && dowWildcard) {
            return true;
        }
        if (domWildcard) {
            return dowOk;
        }
        if (dowWildcard) {
            return domOk;
        }
        return domOk || dowOk;
    }

    public String expr() {
        return expr;
    }

    /**
     * 启动自检：把两个最有代表性的表达式各算三步，打印到 stdout。
     * 假端点的解析断言读的就是这几行——磁盘和请求体只能证明「跑了」，
     * 证明不了「10:00 的下一个触发点是 10:05」这种日历推算对不对。
     */
    public static void selfCheck() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 1, 10, 0);
        Cron step = parse("*/5 * * * *");
        StringBuilder sb = new StringBuilder();
        LocalDateTime t = base;
        for (int i = 0; i < 3; i++) {
            t = step.next(t);
            sb.append(" → ").append(t.format(HM));
        }
        System.out.println("   [cron] 自检 */5 * * * * 从 11-01 10:00" + sb
                + "（期望 10:05 → 10:10 → 10:15）");

        LocalDateTime sunday = LocalDateTime.of(2026, 11, 22, 10, 0);   // 2026-11-22 是周日
        Cron weekday = parse("0 9 * * 1-5");
        LocalDateTime next = weekday.next(sunday);
        String day = "周" + "一二三四五六日".charAt(next.getDayOfWeek().getValue() - 1);
        System.out.println("   [cron] 自检 0 9 * * 1-5 从周日 " + sunday.format(FULL)
                + " → " + day + " " + next.format(FULL) + "（期望一致）");
    }
}
