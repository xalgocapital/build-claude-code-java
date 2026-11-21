package com.xalgocapital.claude.s15;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队：一张成员表，加一个 tick()。
 *
 * 这里最不像「团队」的地方就是 tick()：它不是线程池，不是异步任务，就是按注册顺序
 * 让每个还活着的成员往前走一步。三个人就是三次 HTTP 请求，串着发。
 *
 * 为什么不上真线程：这个系列每一次实测都要能复现。三个线程同时跑，
 * 「谁先认领 T1」就由 OS 调度决定——今天的日志和明天的不一样，文章里的数字和读者
 * 跑出来的也不一样。代价写在 README 里：慢、串行、一个人超时拖住所有人。
 * 换来的是「同样的剧本，同样的顺序」——所以「researcher 先于 indexer 抢 T1」
 * 是能写进断言的事实，不是碰运气。
 */
public final class Team {
    /** lead 在收件箱里的名字。成员的结论都投到这里 */
    public static final String LEAD = "lead";

    private final Map<String, Teammate> members = new LinkedHashMap<>();
    private final Mailbox mailbox;
    private final ObjectMapper mapper;
    private final OpenAICompatClient client;
    private final HookRegistry hooks;
    private final TaskStore tasks;
    private final int max;
    private int tick;
    private int spawned;
    private int delivered;

    public Team(ObjectMapper mapper, OpenAICompatClient client,
                HookRegistry hooks, TaskStore tasks) {
        this.mapper = mapper;
        this.client = client;
        this.hooks = hooks;
        this.tasks = tasks;
        this.max = Integer.parseInt(System.getenv().getOrDefault("TEAM_MAX", "3"));
        // 收件箱落在 .team/<name>/ 下。和 .tasks/ 一个思路：状态在文件里，不在内存里
        this.mailbox = new Mailbox(Path.of(System.getenv().getOrDefault("TEAM_DIR", ".team")), mapper);
        mailbox.register(LEAD);
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
                client, hooks, mailbox, tasks);
        members.put(n, t);
        // 收件箱在起人的时候就建好。等到第一次投递再建，成员第一步收信时目录还不存在
        mailbox.register(n);
        spawned++;
        System.out.println("   [team] + " + n + "（" + t.role() + "）" + members.size() + "/" + max);
        return "Spawned " + n + "（" + members.size() + "/" + max + "）。它现在有自己的一份上下文，"
                + "你给的那段话是它的第一条 user 消息。它不会立刻回话——下一次 tick 才走第一步。";
    }

    /**
     * 投一条消息。to=all 是广播。
     *
     * 返回值里特意写了「下一 tick 才读」：这是模型最容易搞错的一件事，
     * 它投完消息就想看结果，而结果要等下一轮。
     */
    public String send(String from, String to, String text) {
        String target = to == null ? "" : to.strip();
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            return "(error: 消息是空的)";
        }
        if ("all".equals(target)) {
            if (members.isEmpty()) {
                return "(error: 还没有成员)";
            }
            for (String n : members.keySet()) {
                if (!n.equals(from)) {
                    mailbox.post(from, n, body);
                }
            }
            return "Broadcast to " + members.size() + " teammates。它们下一 tick 才读到，这一轮不会有人回你。";
        }
        if (!members.containsKey(target)) {
            return "(error: 没有成员叫 " + target + "；现有成员："
                    + (members.isEmpty() ? "无" : String.join(", ", members.keySet())) + ")";
        }
        mailbox.post(from, target, body);
        return "Queued to " + target + "（收件箱未读 " + mailbox.unread(target) + " 条，下一 tick 才读）";
    }

    /**
     * 轮转一步：每个还活着的成员走一步，它要是给了结论就投回 lead。
     *
     * 返回这一 tick 新投给 lead 的消息。调用方把它们作为 user 消息放进自己的上下文——
     * 成员的话必须是「一条消息」，不能是凭空多出来的记忆。
     */
    public List<Mailbox.Msg> tick() {
        if (members.isEmpty()) {
            return List.of();
        }
        tick++;
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
        return sb.toString();
    }

    public String report() {
        return "成员 " + members.size() + "/" + max + "，累计起过 " + spawned + " 个，"
                + "tick " + tick + " 次，投回 lead " + delivered + " 条";
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
