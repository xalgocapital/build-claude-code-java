package com.xalgocapital.claude.s14;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 死循环检测。
 *
 * 模型卡住的典型形态不是「一直说话」，是「同一个调用一直发」：
 * 读同一个不存在文件、跑同一条失败命令、把同样的参数再提交一次。
 * 它每一轮都觉得自己在尝试，从外面看是原地踏步。
 *
 * 判据只有一个：连续的 (工具名 + 规范化参数) 完全相同。
 *   · 第 3 次 → 提醒一次（注入一条 user 消息，不动 system）
 *   · 第 5 次 → 停（这一轮不再执行，直接收尾）
 *
 * 为什么不看工具结果：结果里带时间戳、带行号、带随机 id，
 * 拿它比指纹反而会把「完全相同」判成「不一样」。
 */
public final class LoopDetector {
    static final int WARN_AT = 3;
    static final int STOP_AT = 5;

    private String lastFp = "";
    private int streak;
    private int warned;
    private final List<String> hits = new ArrayList<>();

    public enum Decision {
        OK, WARN, STOP
    }

    public Decision observe(String name, JsonNode args) {
        String fp = name + "|" + canonical(args);
        if (fp.equals(lastFp)) {
            streak++;
        } else {
            lastFp = fp;
            streak = 1;
        }
        if (streak >= STOP_AT) {
            hits.add(name + " ×" + streak);
            return Decision.STOP;
        }
        if (streak == WARN_AT) {
            warned++;
            return Decision.WARN;
        }
        return Decision.OK;
    }

    public int streak() {
        return streak;
    }

    public String lastCall() {
        int bar = lastFp.indexOf('|');
        return bar < 0 ? lastFp : lastFp.substring(0, bar);
    }

    public int warned() {
        return warned;
    }

    public List<String> hits() {
        return hits;
    }

    /**
     * 参数规范化：字段名排序、去掉空白。
     *
     * 模型每次生成的 JSON 字段顺序都可能不一样（{"a":1,"b":2} 和 {"b":2,"a":1}），
     * 直接比字符串会把同一个调用判成两个。这一行不做，检测器基本失效。
     */
    static String canonical(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (String k : keys) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(k).append(':').append(canonical(node.get(k)));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (JsonNode child : node) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(canonical(child));
            }
            return sb.append(']').toString();
        }
        return node.asText();
    }
}
