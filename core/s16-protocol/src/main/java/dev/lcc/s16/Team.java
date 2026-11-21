package dev.lcc.s16;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队：一张成员表，一个 tick()，加一个协议层。
 *
 * 调度还是 s15 的轮转 tick，一个字没改：按注册顺序让每个活着的成员走一步，
 * 三个人就是三次 HTTP 请求，串着发。这一篇加的都在信封上：
 * Team 把「发信」交给 Protocol，Protocol 管配对、超时、移交。
 *
 * tick() 只多了一行：开头先 protocol.sweep()。放在最前面是因为
 * 「过期」要以 tick 为刻度量——上一轮发出的 request，这一轮开头还没人答，
 * 就该进死信了，不能等成员走完这一步再说。
 */
public final class Team {
    /** lead 在收件箱里的名字。成员的结论都投到这里 */
    public static final String LEAD = "lead";

    private final Map<String, Teammate> members = new LinkedHashMap<>();
    private final Mailbox mailbox;
    private final Protocol protocol;
    private final OpenAICompatClient client;
    private final HookRegistry hooks;
    private final TaskStore tasks;
    private final int max;
    private int tick;
    private int spawned;
    private int delivered;

    public Team(ObjectMapper mapper, OpenAICompatClient client,
                HookRegistry hooks, TaskStore tasks) {
        this.client = client;
        this.hooks = hooks;
        this.tasks = tasks;
        this.max = Integer.parseInt(System.getenv().getOrDefault("TEAM_MAX", "3"));
        // 收件箱落在 .team/<name>/ 下。和 .tasks/ 一个思路：状态在文件里，不在内存里
        this.mailbox = new Mailbox(Path.of(System.getenv().getOrDefault("TEAM_DIR", ".team")), mapper);
        mailbox.register(LEAD);
        this.protocol = new Protocol(mailbox, tasks, mapper);
    }

    public int size() {
        return members.size();
    }

    public Mailbox mailbox() {
        return mailbox;
    }

    /**
     * 起一个成员。name 是它的身份，也是收件箱的目录名。
     *
     * 上限是硬限制，不是提示词里的一句「最多三个人」。写进提示词，模型试第四次时没人拦；
     * 写在这里，第四次直接返回错误，它下一轮就知道住手。
     */
    public String spawn(String name, String role, String prompt) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) {
            return "(error: 成员要有名字)";
        }
        if (LEAD.equals(n)) {
            return "(error: lead 是调用方自己，不能当成员)";
        }
        if (members.containsKey(n)) {
            return "(error: 已经有成员叫 " + n + " 了)";
        }
        if (members.size() >= max) {
            return "(error: 成员上限 TEAM_MAX=" + max + "，现在 " + members.size()
                    + " 个；先把活分完，别再加人)";
        }
        Teammate t = new Teammate(n, role == null ? "" : role.strip(),
                prompt == null || prompt.isBlank() ? "先看任务板，认领一条你能做的。" : prompt.strip(),
                client, hooks, mailbox, tasks, this);
        members.put(n, t);
        // 收件箱在起人的时候就建好。等到第一次投递再建，成员第一步收信时目录还不存在
        mailbox.register(n);
        spawned++;
        System.out.println("   [team] + " + n + "（" + t.role() + "）" + members.size() + "/" + max);
        return "Spawned " + n + "（" + members.size() + "/" + max + "）。它现在有自己的一份上下文，"
                + "你给的那段话是它的第一条 user 消息。它不会立刻回话——下一次 tick 才走第一步。";
    }

    /**
     * 发一封带类型的信。to 可以是另一个成员——s15 里通道是通的，
     * 但成员手里没有发信工具；这一篇把 send_message 发到每个人手里。
     * type 为空退回 s15 的普通消息。
     */
    public String send(String from, String to, String type, String payload, String replyTo) {
        return protocol.send(from, to, type, payload, replyTo);
    }

    /** 任务移交。owner 不变，等收件方下一轮 task_update(status=ACCEPT / REJECT) */
    public String handoff(String from, String taskId, String to) {
        return protocol.send(from, to, Protocol.HANDOFF, taskId, "");
    }

    /** 成员对移交表态。accept 才真正改 owner */
    public String resolveHandoff(String taskId, String by, boolean accept) {
        return protocol.resolveHandoff(taskId, by, accept);
    }

    /**
     * 轮转一步：先清超时，再让每个活着的成员走一步，它要是给了结论就投回 lead。
     *
     * 返回这一 tick 新投给 lead 的消息。调用方把它们作为 user 消息放进自己的上下文——
     * 成员的话必须是「一条消息」，不能是凭空多出来的记忆。
     */
    public List<Mailbox.Msg> tick() {
        if (members.isEmpty()) {
            return List.of();
        }
        tick++;
        int dead = protocol.sweep();
        List<String> active = new ArrayList<>();
        for (Teammate t : members.values()) {
            if (t.state() != Teammate.State.ACTIVE) {
                continue;
            }
            active.add(t.name());
            String report = t.step();
            if (report != null) {
                mailbox.post(t.name(), LEAD, report);
            }
        }
        List<Mailbox.Msg> inbox = mailbox.drain(LEAD);
        delivered += inbox.size();
        System.out.println("   [team] tick " + tick + "：" + active.size() + " 人各走一步（"
                + String.join(",", active) + "）"
                + (dead > 0 ? "；死信 +" + dead : "")
                + (inbox.isEmpty() ? "；没有给你的消息" : "；给你 " + inbox.size() + " 条"));
        return inbox;
    }

    /** 一份状态。system 里那段和 team_status 工具返回的是同一份文字 */
    public String status() {
        if (members.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Team " + members.size() + "/" + max + "：");
        for (Teammate t : members.values()) {
            sb.append("\n  ").append(pad(t.name(), 12))
                    .append(pad(stateOf(t), 14))
                    .append("未读 ").append(mailbox.unread(t.name())).append(" 条")
                    .append("　角色：").append(t.role());
        }
        // 协议行：待答复、死信、孤儿应答。lead 查死信靠这一行，不用翻文件
        sb.append('\n').append(protocol.statusLine());
        return sb.toString();
    }

    public String report() {
        return "成员 " + members.size() + "/" + max + "，累计起过 " + spawned + " 个，"
                + "tick " + tick + " 次，投回 lead " + delivered + " 条；"
                + protocol.report();
    }

    private static String stateOf(Teammate t) {
        return switch (t.state()) {
            case ACTIVE -> "忙（第 " + t.steps() + " 步）";
            case DONE -> "完成";
            case FAILED -> "失败";
        };
    }

    /** 中文字符按两个宽度算，否则第二列永远对不齐 */
    private static String pad(String s, int width) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E80 ? 2 : 1;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = w; i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
