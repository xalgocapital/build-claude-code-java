package com.xalgocapital.claude.springai.s08;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * s08 的 Spring AI 对照：把「上下文压缩」写成一条 advisor。
 *
 * 手写版 s08 在循环开头调 compactor.maybeCompact(messages)；
 * 框架里没有「循环」给你插入，但每次 ChatClient 调用都会穿过 advisor 链，
 * 所以在 before() 里改 request.prompt()，效果完全一样——都是「进模型之前把 messages 改小」。
 *
 * 关键点只有一个：改完之后 tail 不能以 TOOL 消息开头。
 * 那条 TOOL 消息的父 assistant.tool_calls 被删掉了，OpenAI 兼容端点会直接 400。
 */
public class CompactionAdvisor implements BaseAdvisor {
    /** 尾巴至少留几条（两轮工具调用） */
    private static final int KEEP_TAIL = 4;
    /** 开头两条永不动：system 与原始目标 */
    private static final int HEAD = 2;

    private final Function<String, String> summarizer;
    private final int limit;

    public CompactionAdvisor(Function<String, String> summarizer, int limit) {
        this.summarizer = summarizer;
        this.limit = limit;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Message> messages = request.prompt().getInstructions();
        int before = tokens(messages);

        // 到预算 75% 才动手，留余量给这一轮的回复
        if (before < limit * 3 / 4) {
            return request;
        }

        int cut = safeCut(messages);
        if (cut < 0) {
            System.out.println("   [advisor] " + before + " token 已超预算，但找不到安全切点，这轮不压");
            return request;
        }

        String digest = summarizer.apply(transcript(messages, HEAD, cut));
        if (digest == null || digest.isBlank()) {
            return request;
        }

        List<Message> rebuilt = new ArrayList<>();
        rebuilt.add(messages.get(0));                       // system
        rebuilt.add(messages.get(1));                       // 原始目标
        rebuilt.add(new UserMessage("<summary>\n（前 " + (cut - HEAD) + " 条消息已压缩移除，原上下文约 "
                + before + " token）\n" + digest.strip() + "\n</summary>\n\n继续当前任务。"));
        rebuilt.addAll(messages.subList(cut, messages.size()));

        System.out.println("   [advisor] 压缩：" + messages.size() + " 条 → " + rebuilt.size() + " 条，"
                + before + " → " + tokens(rebuilt) + " token（估算）");

        // 框架给的改写入口：换个 prompt 继续往下走，后面的 advisor 和模型看到的就是压缩后的版本
        return request.mutate().prompt(new Prompt(rebuilt)).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;   // 压缩只关心输入端
    }

    /** 链里的顺序：排在记忆 advisor 之后（压缩看的是已经拼好的完整上下文） */
    @Override
    public int getOrder() {
        return 100;
    }

    /**
     * 从后往前找切点：先找「回合结束」（ASSISTANT 且不带 tool_calls），
     * 找不到就退到「非 TOOL 消息」。切在 TOOL 上就会产生孤立的 tool_call_id。
     */
    private int safeCut(List<Message> messages) {
        int latest = messages.size() - KEEP_TAIL;
        for (int i = latest; i >= HEAD; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == MessageType.ASSISTANT
                    && !((AssistantMessage) m).hasToolCalls()) {
                return i;
            }
        }
        for (int i = latest; i >= HEAD; i--) {
            if (messages.get(i).getMessageType() != MessageType.TOOL) {
                return i;
            }
        }
        return -1;
    }

    /** 摊平成纯文本送给摘要模型，单条截断——否则摘要请求和原文一样大 */
    private String transcript(List<Message> messages, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Message m = messages.get(i);
            String text = m.getText() == null ? "" : m.getText();
            switch (m.getMessageType()) {
                case ASSISTANT -> {
                    AssistantMessage am = (AssistantMessage) m;
                    if (am.hasToolCalls()) {
                        am.getToolCalls().forEach(c ->
                                sb.append("助手 → 调用 ").append(c.name())
                                        .append('(').append(clip(c.arguments(), 200)).append(")\n"));
                    } else {
                        sb.append("助手: ").append(clip(text, 600)).append('\n');
                    }
                }
                case TOOL -> {
                    for (ToolResponseMessage.ToolResponse r : ((ToolResponseMessage) m).getResponses()) {
                        sb.append("工具结果: ").append(clip(r.responseData(), 600)).append('\n');
                    }
                }
                default -> sb.append("用户: ").append(clip(text, 600)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + " …(截断)";
    }

    /** 和手写版同一套粗估：汉字算 1、英文单词算 1 */
    static int tokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += tokens(m.getText() == null ? "" : m.getText());
            if (m.getMessageType() == MessageType.ASSISTANT) {
                for (AssistantMessage.ToolCall c : ((AssistantMessage) m).getToolCalls()) {
                    total += tokens(c.name()) + tokens(c.arguments());
                }
            }
            // 工具结果不在 getText() 里：ToolResponseMessage 的正文在 responses[*].responseData()
            if (m.getMessageType() == MessageType.TOOL) {
                for (ToolResponseMessage.ToolResponse r : ((ToolResponseMessage) m).getResponses()) {
                    total += tokens(r.responseData());
                }
            }
        }
        return total;
    }

    private static int tokens(String text) {
        int cjk = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
                inWord = false;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    words++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return cjk + words;
    }
}
