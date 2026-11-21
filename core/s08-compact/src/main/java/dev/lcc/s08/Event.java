package dev.lcc.s08;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次 hook 触发的上下文。四个事件覆盖一个完整的 agent cycle：
 *   UserPromptSubmit  用户输入后、进 LLM 前
 *   PreToolUse        工具执行前
 *   PostToolUse       工具执行后
 *   Stop              循环即将退出时
 *
 * 一个 record 把四种事件要用的数据都装上，用不到的字段为 null。
 * 真实 CC 有 27 个事件、14 个结果字段；这里收敛成 4 事件 + 一个字符串返回值，
 * 心智负担降到最低——要证明的是「hook 挂在哪个点、怎么影响执行」。
 */
public record Event(
        String name,          // 事件名
        String query,         // UserPromptSubmit: 用户输入
        String toolName,      // PreToolUse / PostToolUse: 工具名
        JsonNode args,        // PreToolUse / PostToolUse: 工具参数
        String output,        // PostToolUse: 工具输出
        int toolCount         // Stop: 本会话工具调用数
) {
    public static Event userPrompt(String query) {
        return new Event("UserPromptSubmit", query, null, null, null, 0);
    }

    public static Event preTool(String tool, JsonNode args) {
        return new Event("PreToolUse", null, tool, args, null, 0);
    }

    public static Event postTool(String tool, JsonNode args, String output) {
        return new Event("PostToolUse", null, tool, args, output, 0);
    }

    public static Event stop(int toolCount) {
        return new Event("Stop", null, null, null, null, toolCount);
    }
}
