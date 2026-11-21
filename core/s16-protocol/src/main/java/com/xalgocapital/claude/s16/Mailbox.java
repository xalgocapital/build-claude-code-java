package com.xalgocapital.claude.s16;

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
 * s15 起的底子，这一篇只动了两处：
 *   1. postEnvelope()：一行信封 JSON（id/type/payload/replyTo/deadline），和普通消息同一个文件
 *   2. Msg.render() 按有无 type 分叉——信封渲染成 envelope 形态，普通消息还是 message 形态
 *
 * 读的语义一个字没改：drain() 返回「这一 tick 新到的」，cursor 存盘，重启不重投。
 */
public final class Mailbox {
    /**
     * 一条消息。envType 非空表示这是信封：渲染走 envelope 形态，id 和 replyTo
     * 必须出现在上下文里，否则收件方没法按协议答复。
     */
    public record Msg(String from, String to, String text, String at,
                      String envId, String envType, String replyTo) {
        String render() {
            if (envType == null || envType.isBlank()) {
                return "<message from=\"" + from + "\" at=\"" + at + "\">" + text + "</message>";
            }
            return new Envelope(envId, from, to, envType, text, replyTo, "").render();
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

    public Path root() {
        return root;
    }

    /** 收件人存在吗。lead 是常驻的，成员要看起没起过 */
    public boolean known(String name) {
        return cursor.containsKey(name) || Team.LEAD.equals(name);
    }

    /** 成员名单（不含 lead）。广播用 */
    public List<String> memberNames() {
        List<String> out = new ArrayList<>();
        for (String n : cursor.keySet()) {
            if (!Team.LEAD.equals(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /** 投一条普通消息。只追加一行，顺手把目录建出来——「人还没到、消息先到」是最常见的情况 */
    public void post(String from, String to, String text) {
        try {
            Path dir = root.resolve(to);
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

    /** 投一封信。信封和普通消息同一个文件，type 字段区分 */
    public void postEnvelope(Envelope env) {
        try {
            Path dir = root.resolve(env.to());
            Files.createDirectories(dir);
            ObjectNode n = env.toJson(mapper, LocalTime.now().format(TS));
            Files.writeString(dir.resolve("inbox.jsonl"), mapper.writeValueAsString(n) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            cursor.putIfAbsent(env.to(), 0);
        } catch (IOException e) {
            throw new RuntimeException("投递失败 " + env.from() + " → " + env.to(), e);
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
                String type = n.path("type").asText("");
                out.add(new Msg(n.path("from").asText(""), n.path("to").asText(name),
                        type.isEmpty() ? n.path("text").asText("") : n.path("payload").asText(""),
                        n.path("at").asText(""),
                        n.path("id").asText(""), type, n.path("replyTo").asText("")));
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
