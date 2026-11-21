package com.xalgocapital.claude.springai.s04;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * 一个自定义环绕 advisor——这就是 Spring AI 里「hook」的等价物。
 *
 * 手写版 s04 的 hook 是注册表 + trigger(event)，挂在循环周围的四个点上。
 * Spring AI 没有 register_hook(event, callback) 这套 API，它把扩展点做成了
 * advisor 链：每个 advisor 实现 before()/after()，框架在调用前后自动环绕执行。
 *
 * 对照关系：
 *   s04 hook 的「PreToolUse / PostToolUse 前后环绕」
 *     ≈ BaseAdvisor 的 before() / after()
 *   s04 的「注册多个 hook 到同一事件、按序执行」
 *     ≈ 把多个 advisor 加进链，用 getOrder() 控制顺序
 *
 * 差别：hook 由你的循环显式 trigger(event)；advisor 由框架在每次 ChatClient
 * 调用时自动环绕。前者你完全看得见触发点，后者是框架替你挂的。
 */
public class LoggingAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String text = request.prompt().getUserMessage() == null
                ? ""
                : request.prompt().getUserMessage().getText();
        System.out.println("[advisor·before] 收到输入: " + abbreviate(text));
        return request;   // 返回 request = 放行；这里也可以改 prompt 实现「输入注入」
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String result = response.chatResponse() == null || response.chatResponse().getResult() == null
                ? ""
                : response.chatResponse().getResult().getOutput().getText();
        System.out.println("[advisor·after]  收到输出: " + abbreviate(result));
        return response;  // 返回 response = 原样透传；也可以包装成「输出检查 / 拦截」
    }

    @Override
    public String getName() {
        return "LoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;   // 链尾，包在最外层
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
