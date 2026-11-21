package dev.lcc.s16;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队协议：把「一句话」升级成「一封带类型的信」，并负责配对、超时、移交三件事。
 *
 * s15 的 send 只是投递函数：post(from, to, text)。发出去就发出去了，
 * 有没有人答、什么时候必须答、答了算不算数，一概不知道。成员之间要协作，
 * 光能传话不够——得有一方明确欠着另一方一个答复。
 *
 * 这里管三件事，全部是代码约束而不是提示词：
 *   1. 配对：response 的 replyTo 必须指回一条在等的 request，对不上就拒收（orphan_reply）
 *   2. 超时：request 过了 deadline 还没被答复，进发件人的 .team/<from>/dead.jsonl（死信）
 *   3. 移交：handoff 只登记不生效，收件方 task_update(status=ACCEPT) 才真正改 owner
 *
 * naive 模式（PROTOCOL_MODE=naive）把 1 和 2 都关掉：不查 replyTo、不清超时。
 * 结果是死信永远为 0、孤儿应答被当成正常回执投进收件箱——对比数字在 README 里。
 */
public final class Protocol {
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String BROADCAST = "broadcast";
    public static final String HANDOFF = "handoff";

    /** 答复期限。默认 800ms——本地假端点一轮几十 ms，配轮转 tick 才测得出「过期」 */
    public static final long TIMEOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("PROTOCOL_TIMEOUT_MS", "800"));

    static final boolean NAIVE = "naive".equals(
            System.getenv().getOrDefault("PROTOCOL_MODE", "checked"));

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 一条还没被答复的 request。expiresAt 是 wall clock，sweep() 按它清死信 */
    record Pending(Envelope env, long expiresAtMs) {}

    private final Mailbox mailbox;
    private final TaskStore tasks;
    private final ObjectMapper mapper;

    /** 在等的 request，key 是信封 id */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /** 在等的 handoff，key 是任务 id，value 是移交对象 */
    private final Map<String, String> handoffs = new LinkedHashMap<>();

    private final Map<String, Integer> seq = new LinkedHashMap<>();
    private int deadCount;
    private int orphanReplies;
    private int matched;
    private final Map<String, Integer> byType = new LinkedHashMap<>();

    Protocol(Mailbox mailbox, TaskStore tasks, ObjectMapper mapper) {
        this.mailbox = mailbox;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    /**
     * 发一封带类型的信。返回给模型的字符串里写清「配对成功/被拒收」——
     * 拒绝不能只抛栈，模型读得到错误串才会换做法。
     */
    String send(String from, String to, String type, String payload, String replyTo) {
        String t = type == null ? "" : type.strip();
        String body = payload == null ? "" : payload.strip();
        if (body.isEmpty()) {
            return "(error: 消息是空的)";
        }
        if (t.isEmpty()) {
            // 没写 type 就退回 s15 的行为：一封普通信，不配对、不超时
            return deliverPlain(from, to, body);
        }
        if (!List.of(REQUEST, RESPONSE, BROADCAST, HANDOFF).contains(t)) {
            return "(error: 未知类型 " + t + "；可用 request / response / broadcast / handoff)";
        }
        return switch (t) {
            case REQUEST -> sendRequest(from, to, body);
            case RESPONSE -> sendResponse(from, to, body, replyTo == null ? "" : replyTo.strip());
            case BROADCAST -> sendBroadcast(from, body);
            case HANDOFF -> sendHandoff(from, to, body);
            default -> "(error: unreachable)";
        };
    }

    /** s15 的老路。成员的结论投回 lead 走这里，不占协议的账 */
    private String deliverPlain(String from, String to, String body) {
        if ("all".equals(to)) {
            return broadcastPlain(from, body);
        }
        String err = checkTarget(from, to);
        if (err != null) {
            return err;
        }
        mailbox.post(from, to, body);
        return "Queued to " + to + "（普通消息，未读 " + mailbox.unread(to) + " 条，下一 tick 才读）";
    }

    private String broadcastPlain(String from, String body) {
        List<String> targets = membersFrom(from);
        if (targets.isEmpty()) {
            return "(error: 还没有成员)";
        }
        for (String n : targets) {
            mailbox.post(from, n, body);
        }
        return "Broadcast to " + targets.size() + " teammates。它们下一 tick 才读到，这一轮不会有人回你。";
    }

    /**
     * request：登记进 pending，deadline 写在信封上。
     * 超不超时不看提示词，看 sweep()。
     */
    private String sendRequest(String from, String to, String body) {
        String err = checkTarget(from, to);
        if (err != null) {
            return err;
        }
        String id = "R" + bump(REQUEST);
        long expiresAt = System.currentTimeMillis() + TIMEOUT_MS;
        Envelope env = new Envelope(id, from, to, REQUEST, body, "", fmt(expiresAt));
        pending.put(id, new Pending(env, expiresAt));
        byType.merge(REQUEST, 1, Integer::sum);
        mailbox.postEnvelope(env);
        return "Request " + id + " → " + to + "（deadline " + env.deadline()
                + "，未答复发 dead.jsonl）。它下一 tick 才读到，答复要带 replyTo=" + id;
    }

    /**
     * response：replyTo 必须指回一条在等的 request。
     * 对不上就拒收——不然「答复」两个字没有任何含义，谁都能装作在回答。
     */
    private String sendResponse(String from, String to, String body, String replyTo) {
        String err = checkTarget(from, to);
        if (err != null) {
            return err;
        }
        if (!NAIVE && !pending.containsKey(replyTo)) {
            orphanReplies++;
            System.out.println("   [protocol] orphan_reply：" + from + " 的 response replyTo="
                    + (replyTo.isBlank() ? "(空)" : replyTo) + " 没有对应的待答复请求，拒收");
            return "(error: orphan_reply: replyTo " + (replyTo.isBlank() ? "(空)" : replyTo)
                    + " 没有对应的待答复请求，这条 response 被拒收。先确认你收到的是哪一条 request id)";
        }
        String id = "E" + bump(RESPONSE);
        byType.merge(RESPONSE, 1, Integer::sum);
        mailbox.postEnvelope(new Envelope(id, from, to, RESPONSE, body, replyTo, ""));
        if (!NAIVE) {
            pending.remove(replyTo);
            matched++;
        }
        return "Response " + id + " → " + to + "（replyTo=" + replyTo + "，配对"
                + (NAIVE ? "未检查（naive）" : "成立") + "）";
    }

    /** broadcast：所有人 + lead。lead 只看得到广播和自己的信，这是它唯一的全局视野 */
    private String sendBroadcast(String from, String body) {
        List<String> targets = membersFrom(from);
        if (targets.isEmpty()) {
            return "(error: 还没有成员)";
        }
        String id = "B" + bump(BROADCAST);
        byType.merge(BROADCAST, 1, Integer::sum);
        for (String n : targets) {
            mailbox.postEnvelope(new Envelope(id, from, n, BROADCAST, body, "", ""));
        }
        return "Broadcast " + id + " to " + targets.size() + " 个收件人（含 lead）。下一 tick 才读。";
    }

    /**
     * handoff：只登记，不改 owner。accept 才改——s15 的认领是「谁先写谁赢」，
     * s16 的移交是「原 owner 主动让」，同一个 owner 唯一性问题从另一头解。
     */
    private String sendHandoff(String from, String to, String taskId) {
        String err = checkTarget(from, to);
        if (err != null) {
            return err;
        }
        TaskStore.Task t = tasks.get(taskId);
        // 拒收要让模型看得见：任务不存在，或者你不是 owner
        if (t == null) {
            return "(error: handoff 被拒：没有任务 " + taskId + ")";
        }
        boolean isOwner = t.owner().equals(from) || (t.owner().isBlank() && Team.LEAD.equals(from));
        if (!isOwner) {
            return "(error: handoff 被拒：" + taskId + " 的 owner 是 "
                    + (t.owner().isBlank() ? "(空)" : t.owner()) + "，不是你)";
        }
        String id = "H" + bump(HANDOFF);
        byType.merge(HANDOFF, 1, Integer::sum);
        handoffs.put(taskId, to);
        mailbox.postEnvelope(new Envelope(id, from, to, HANDOFF,
                "任务 " + taskId + " " + t.subject() + " → 移交给你。下一轮 task_update(id, status=ACCEPT 或 REJECT)",
                "", ""));
        return "Handoff " + id + "：" + taskId + " → " + to
                + "（owner 未变，等对方下一轮 ACCEPT / REJECT）";
    }

    /** 收件方表态。ACCEPT 才真正改 owner；只有被移交给的那个人有资格表态 */
    String resolveHandoff(String taskId, String by, boolean accept) {
        String to = handoffs.get(taskId);
        if (to == null) {
            return "(error: 没有待答复的移交：" + taskId + " 要么没被 handoff，要么已经被答复)";
        }
        if (!to.equals(by)) {
            return "(error: 这条 handoff 是给 " + to + " 的，不是给你的)";
        }
        handoffs.remove(taskId);
        if (!accept) {
            return taskId + " 的移交被拒绝，owner 不变";
        }
        TaskStore.Task t = tasks.transfer(taskId, by);
        return taskId + " owner 已移交 → " + t.owner() + "（accept）。现在是你的活，按正常流程认领推进。";
    }

    /**
     * 清一次超时。放在每轮 tick 的最前面：request 过了 deadline 还没人答，
     * 就从 pending 摘下来，写进发件人的 dead.jsonl。
     *
     * naive 模式跳过——于是被无视的 request 永远「在等」，死信永远为 0。
     */
    int sweep() {
        if (NAIVE) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            if (e.getValue().expiresAtMs() < now) {
                expired.add(e.getKey());
            }
        }
        for (String id : expired) {
            Pending p = pending.remove(id);
            deadCount++;
            writeDead(p.env());
        }
        return expired.size();
    }

    /** 死信进发件人的目录。读了不删的老规矩在这里不适用：死信是台账，只有追加 */
    private void writeDead(Envelope env) {
        Path f = mailbox.root().resolve(env.from()).resolve("dead.jsonl");
        try {
            Files.createDirectories(f.getParent());
            ObjectNode n = env.toJson(mapper, LocalTime.now().format(TS));
            n.put("deadAt", LocalTime.now().format(TS));
            n.put("reason", "timeout " + Protocol.TIMEOUT_MS + "ms 内未答复");
            Files.writeString(f, mapper.writeValueAsString(n) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("   [protocol] 死信 " + env.id() + "（" + env.from() + " → "
                    + env.to() + "）进 " + env.from() + "/dead.jsonl");
        } catch (IOException e) {
            System.out.println("   [protocol] 死信写盘失败：" + e.getMessage());
        }
    }

    /** team_status 的协议行。lead 查死信就靠这一行，不用翻文件 */
    String statusLine() {
        return "协议：待答复 " + pending.size() + " 条，死信 " + deadCount
                + " 条，孤儿应答 " + orphanReplies + " 条"
                + (handoffs.isEmpty() ? "" : "，待表态移交 " + handoffs.size() + " 条")
                + (NAIVE ? "（naive：不查 replyTo、不清超时）" : "");
    }

    String report() {
        int total = byType.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder sb = new StringBuilder("信封 ").append(total).append(" 封");
        for (String t : List.of(REQUEST, RESPONSE, BROADCAST, HANDOFF)) {
            if (byType.containsKey(t)) {
                sb.append("，").append(t).append(" ").append(byType.get(t));
            }
        }
        sb.append("；配对成功 ").append(matched).append("，死信 ").append(deadCount)
                .append("，孤儿应答 ").append(orphanReplies);
        return sb.toString();
    }

    private String checkTarget(String from, String to) {
        if (Team.LEAD.equals(to)) {
            return null;    // 投回 lead 永远合法，lead 的收件箱是常驻的
        }
        if (mailbox.known(to)) {
            return null;
        }
        return "(error: 没有成员叫 " + to + "，也没有 lead；收件人写错等于把信投进黑洞)";
    }

    /** 广播的收件人：除发件人外的全部成员，再加 lead */
    private List<String> membersFrom(String from) {
        List<String> out = new ArrayList<>(mailbox.memberNames());
        out.remove(from);
        if (!Team.LEAD.equals(from)) {
            out.add(Team.LEAD);
        }
        return out;
    }

    private int bump(String type) {
        return seq.merge(type, 1, Integer::sum);
    }

    private static String fmt(long expiresAtMs) {
        return LocalTime.now().plusNanos((expiresAtMs - System.currentTimeMillis()) * 1_000_000)
                .format(TS);
    }
}
