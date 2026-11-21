package dev.lcc.s15;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 一人一个收件箱：.team/<name>/inbox.jsonl 只追加，.team/<name>/cursor 记读到第几行。
 *
 * s06 的 subagent 没有这个东西——它叫一次、拿结论、就没了，中途没人能跟它说第二句话。
 * 读了不删是刻意的：删掉就没有历史，重启后想知道「成员上次收到什么」只能翻日志。
 * 另存 cursor 而不是改写 jsonl：追加是 O(1)，改写是 O(n)。
 *
 * 单线程用（调度是轮转 tick），所以没有锁。真并发下同一行可能被读两次。
 */
public final class Mailbox {
    /** 一条消息。to 在落盘时已经展开成具体的人，不存在「all」这种收件人 */
    public record Msg(String from, String to, String text, String at) {
        /** 塞进 messages 时的样子。带 from 很重要：模型要知道这句话是谁说的 */
        String render() {
            return "<message from=\"" + from + "\" at=\"" + at + "\">" + text + "</message>";
        }
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path root;
    private final ObjectMapper mapper;
    /** 每个人读到第几行。启动时从 cursor 文件读回来，所以重启不会把旧消息再投一遍 */
    private final Map<String, Integer> cursor = new LinkedHashMap<>();

    public Mailbox(Path root, ObjectMapper mapper) {
        this.root = root;
        this.mapper = mapper;
    }

    /** 登记一个收件箱。重复登记是幂等的——起人和重启都会调 */
    public void register(String name) {
        Path dir = root.resolve(name);
        try {
            Files.createDirectories(dir);
            Path c = dir.resolve("cursor");
            int n = Files.exists(c) ? Integer.parseInt(Files.readString(c).strip()) : 0;
            cursor.put(name, n);
        } catch (IOException | NumberFormatException e) {
            // cursor 坏了不致命：退回 0，最坏是把读过的消息再读一遍
            System.out.println("   [mailbox] " + name + " 的 cursor 读不了，从 0 开始：" + e.getMessage());
            cursor.put(name, 0);
        }
    }

    /** 投一条。只追加一行，顺手把目录建出来——「人还没到、消息先到」是最常见的情况 */
    public void post(String from, String to, String text) {
        Path dir = root.resolve(to);
        try {
            Files.createDirectories(dir);
            ObjectNode n = mapper.createObjectNode();
            n.put("from", from);
            n.put("to", to);
            n.put("at", LocalTime.now().format(TS));
            n.put("text", text);
            Files.writeString(dir.resolve("inbox.jsonl"), mapper.writeValueAsString(n) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            cursor.putIfAbsent(to, 0);
        } catch (IOException e) {
            throw new RuntimeException("投递失败 " + from + " → " + to, e);
        }
    }

    /**
     * 取走没读过的，并把 cursor 推到末尾。
     *
     * 返回「这一 tick 新到的」而不是「全部」。否则成员每一步都会重读同一句话，
     * 然后以为事情还没做完。
     */
    public List<Msg> drain(String name) {
        List<Msg> all = readAll(name);
        int from = Math.min(cursor.getOrDefault(name, 0), all.size());
        List<Msg> out = new ArrayList<>(all.subList(from, all.size()));
        cursor.put(name, all.size());
        writeCursor(name, all.size());
        return out;
    }

    /** 还没读的条数。team_status 用它回答「谁的消息没人看」 */
    public int unread(String name) {
        return Math.max(0, readAll(name).size() - cursor.getOrDefault(name, 0));
    }

    private List<Msg> readAll(String name) {
        Path f = root.resolve(name).resolve("inbox.jsonl");
        List<Msg> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = mapper.readTree(line);
                out.add(new Msg(n.path("from").asText(""), n.path("to").asText(name),
                        n.path("text").asText(""), n.path("at").asText("")));
            }
        } catch (IOException e) {
            System.out.println("   [mailbox] " + name + " 的收件箱读不了：" + e.getMessage());
        }
        return out;
    }

    private void writeCursor(String name, int value) {
        try {
            Files.writeString(root.resolve(name).resolve("cursor"), String.valueOf(value));
        } catch (IOException e) {
            // 写不进去只会导致下次重读，不值得让整个会话停下
            System.out.println("   [mailbox] " + name + " 的 cursor 存不了：" + e.getMessage());
        }
    }
}
