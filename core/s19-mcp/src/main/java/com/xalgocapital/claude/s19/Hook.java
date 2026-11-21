package com.xalgocapital.claude.s19;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * s04 的全部新东西：一个 hook 回调接口。
 *
 * hook 是「挂在循环上的一段扩展逻辑」。它吃进一个事件上下文，返回一个字符串：
 *   - 返回 null  → 放行，什么也不改（绝大多数 hook 走这条路）
 *   - 返回非 null → hook 说「停」或「改」，具体含义由事件决定：
 *       PreToolUse      → 阻止本次工具执行，字符串作为拒绝原因喂回模型
 *       Stop            → 强制续跑，字符串作为新的一轮用户输入注入
 *       UserPromptSubmit / PostToolUse → 返回值不用，只当副作用
 *
 * 用 Java 函数式接口表达「注册一个回调」，等价于 register_hook(event, callback) 这个动作。
 */
@FunctionalInterface
public interface Hook {
    String onEvent(Event event);
}
