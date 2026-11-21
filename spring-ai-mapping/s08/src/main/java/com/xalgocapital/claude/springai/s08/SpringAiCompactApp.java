package com.xalgocapital.claude.springai.s08;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * s08 的 Spring AI 对照：上下文压缩在框架里落在哪。
 *
 * 两件事要分别看清：
 *   1. 框架自带的 ChatMemory 只有「窗口」——超了就把最老的扔掉，不留摘要
 *   2. 「压成摘要」得自己写成 advisor，挂在 before() 上；顺带能看到同一套边界规则
 *
 * 这个 demo 不起真实循环，直接拿一段造好的历史喂进去，看两个东西各自怎么处理。
 */
@SpringBootApplication
public class SpringAiCompactApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiCompactApp.class, args);
    }

    @Bean
    CommandLineRunner demo(ChatClient.Builder builder) {
        return args -> {
            // 摘要用的客户端：一定要分叉出去，不能和下面那个共用同一个 Builder——
            // defaultAdvisors() 改的是 Builder 自己，两个客户端会共享同一份 advisor 列表，
            // 摘要调用就会被压缩 advisor 再拦一次，套娃。
            ChatClient plain = builder.build();
            ChatClient compacting = plain.mutate()
                    .defaultAdvisors(new CompactionAdvisor(
                            text -> plain.prompt()
                                    .system("你在压缩一段 Agent 会话。保留：已确认的结论、改过的文件、"
                                            + "失败的尝试、下一步。不超过 300 字。")
                                    .user(text)
                                    .call()
                                    .content(),
                            300))
                    .build();

            List<Message> history = demoHistory();
            System.out.println("\n输入历史：" + history.size() + " 条，约 "
                    + CompactionAdvisor.tokens(history) + " token（估算）");

            String answer = compacting.prompt(new Prompt(history)).call().content();
            System.out.println("\n最终回答: " + answer);

            contrast(history);
        };
    }

    /** 同一段历史交给框架自带的窗口记忆，看它留下什么 */
    private void contrast(List<Message> history) {
        System.out.println("\n=== MessageWindowChatMemory(maxMessages=4) ===");
        window("带工具调用的历史", history, 4);

        // 再用一段纯文本历史试一次，排除「工具调用把窗口搞乱」的可能
        List<Message> plainHistory = new ArrayList<>();
        plainHistory.add(new SystemMessage("You are a helpful coding agent."));
        plainHistory.add(new UserMessage("把慢查询日志过一遍，挑出最慢的三条 SQL。"));
        for (int i = 1; i <= 8; i++) {
            plainHistory.add(new AssistantMessage("第 " + i + " 轮的中间结论"));
            plainHistory.add(new UserMessage("继续第 " + (i + 1) + " 步"));
        }
        window("纯文本历史", plainHistory, 4);
    }

    private void window(String label, List<Message> history, int max) {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(max).build();
        memory.add("demo", history);
        List<Message> kept = memory.get("demo");
        System.out.println(label + "：喂进去 " + history.size() + " 条，留下 " + kept.size() + " 条 "
                + kept.stream().map(m -> m.getMessageType().name()).toList());
        System.out.println("  原始目标还在吗：" + kept.stream().anyMatch(m ->
                m.getMessageType().name().equals("USER") && m.getText() != null
                        && m.getText().contains("慢查询")));
    }

    /**
     * 造一段像样的历史：目标 + 六轮「读日志」工具调用。
     * 每条工具结果约 300 字，六轮下来刚好把 300 token 的预算顶穿。
     */
    private List<Message> demoHistory() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are a helpful coding agent."));
        messages.add(new UserMessage("把慢查询日志过一遍，挑出最慢的三条 SQL，给索引建议，写到 notes/verdict.txt。"));

        for (int i = 1; i <= 6; i++) {
            String id = "call_" + i;
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            id, "function", "read_file",
                            "{\"path\":\"data/slow-" + i + ".log\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            id, "read_file", logContent(i))))
                    .build());
        }
        return messages;
    }

    private String logContent(int i) {
        StringBuilder sb = new StringBuilder();
        for (int j = 1; j <= 6; j++) {
            sb.append("2026-11-20T10:0").append(j).append(":00Z ")
                    .append(4000 - i * 60 - j * 10).append("ms /api/orders ")
                    .append("SELECT id, user_id, total FROM orders WHERE user_id = ")
                    .append(10000 + j).append(" AND created_at > '2026-11-01';\n");
        }
        sb.append("FILE-").append(i).append("-END 共 6 条慢查询");
        return sb.toString();
    }
}
