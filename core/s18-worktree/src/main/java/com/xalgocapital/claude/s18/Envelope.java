package com.xalgocapital.claude.s18;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一封带类型的信。s15 的消息只有一句 text，能传话，传不了「这是一件需要你答复的事」。
 *
 * 四种 type 各管一件事：
 *   request   需要收件方答复。有 id，有 deadline
 *   response  对某条 request 的答复，replyTo 指回那条 request 的 id
 *   broadcast 发给所有人（lead 也会收到一份——lead 只看得到广播和自己的信）
 *   handoff   把某个任务的 owner 移交给别人，收件方必须 accept 或 reject
 *
 * deadline 是答复的截止时刻（wall clock）。超时没收到的，进发件人的 dead.jsonl——
 * 这件事在 Protocol 里做，信封只负责把截止时刻写在信封上，让收件方看得见。
 */
public record Envelope(String id, String from, String to, String type,
                       String payload, String replyTo, String deadline) {

    /** 投给模型时的样子。id 必须出现在上下文里，否则收件方无从知道该拿什么填 replyTo */
    public String render() {
        String ref = replyTo == null || replyTo.isBlank() ? "-" : replyTo;
        String tail = deadline == null || deadline.isBlank() ? "" : "（" + deadline + " 前答复）";
        return "<envelope id=\"" + id + "\" from=\"" + from + "\" type=\"" + type
                + "\" replyTo=\"" + ref + "\">" + payload + tail + "</envelope>";
    }

    /** 落盘一行的形态。和 s15 的 inbox.jsonl 同一个文件，多出来的字段旧消息没有 */
    public ObjectNode toJson(ObjectMapper mapper, String at) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("from", from);
        n.put("to", to);
        n.put("at", at);
        n.put("type", type);
        n.put("payload", payload);
        n.put("replyTo", replyTo == null ? "" : replyTo);
        n.put("deadline", deadline == null ? "" : deadline);
        return n;
    }

    public static Envelope fromJson(JsonNode n, String to) {
        return new Envelope(n.path("id").asText(""), n.path("from").asText(""),
                n.path("to").asText(to), n.path("type").asText(""),
                n.path("payload").asText(n.path("text").asText("")),
                n.path("replyTo").asText(""), n.path("deadline").asText(""));
    }
}
