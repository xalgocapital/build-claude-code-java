package com.xalgocapital.claude.s17;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * s09 的全部新东西：跨会话记忆。
 *
 * 前面八篇所有东西都在 messages 里，而 messages 是本轮的、会满的、会被压的。
 * 记忆要解决的是另一件事：昨天告诉你「这个项目的错误返回是 {"error":{code,message}}」，
 * 今天新开一个会话，你还得再说一遍——因为昨天那条消息已经被压掉了。
 *
 * 所以记忆必须活在 messages 外面：磁盘上两个文件，每一轮重新拼进 system prompt。
 * 上下文压缩（s08）碰不到它，进程重启也还在。
 *
 * 四件事，正好对上四个最容易做错的地方：
 *   1. 存什么    正文超过 MAX_BODY 直接拒——记忆是一句可复用的事实，不是过程记录
 *   2. 存哪儿    项目级 / 用户级两个文件，同名 key 项目级胜（约定比偏好具体）
 *   3. 怎么读    索引常驻（每条一行），正文靠 memory_read 按需取
 *   4. 怎么防脏  同 key 覆盖不追加、上限 MAX_ENTRIES 淘汰最久没读的、工具不收路径
 */
public class MemoryStore {
    /** 单条记忆正文上限。超了不是截断，是拒绝——截断会把一句话切成半句，比没写更糟 */
    public static final int MAX_BODY = 400;
    /** 每个 scope 的条数上限。超了淘汰最久没被读过的 */
    public static final int MAX_ENTRIES = 30;
    /** 目录行里每条显示多少字：够认出是哪件事，不够就不看 */
    private static final int DIGEST = 36;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static final String WRITE_DESCRIPTION =
            "Save one reusable fact to long-term memory so a future session has it without being told again. "
            + "Use it when a conclusion is confirmed (a project convention, a fixed API shape, a trap you just "
            + "hit and do not want to hit twice) or when the user says to remember something. "
            + "scope=project is for facts about this repository and is shared with the team; "
            + "scope=user is for preferences that follow the person across projects. "
            + "key is a short stable slug; writing the same key again REPLACES the old value. "
            + "Store one sentence-level fact per key. Do not paste process logs, file contents or task history — "
            + "anything over " + MAX_BODY + " characters is rejected.";

    public static final String READ_DESCRIPTION =
            "Read the full text of one memory by key. The system prompt only carries a one-line index, "
            + "so call this when the work depends on the details of a remembered fact.";

    /** 一条记忆：key 是身份，text 是内容，reads/lastRead 给淘汰策略用 */
    public record Entry(String key, String scope, String text, String updated, String lastRead, int reads) {
        Entry touched(String now) {
            return new Entry(key, scope, text, updated, now, reads + 1);
        }

        Entry rewritten(String newText, String now) {
            // 覆盖：内容换掉，读取计数留着——这条记忆本身还是同一条
            return new Entry(key, scope, newText, now, lastRead, reads);
        }

        /** 目录行里的那一截。换行压成空格，不然一条记忆会把目录撑成好几行 */
        String digest() {
            String flat = text.replaceAll("\\s+", " ").strip();
            return flat.length() <= DIGEST ? flat : flat.substring(0, DIGEST) + "…";
        }
    }

    private final Path projectFile;
    private final Path userFile;
    private final Map<String, Entry> project = new LinkedHashMap<>();
    private final Map<String, Entry> user = new LinkedHashMap<>();

    private int readsHit;
    private int readsMiss;
    private int writesAccepted;
    private int writesRejected;
    /** 每写一次 +1。AgentLoop 靠它判断「system prompt 里的目录该重拼了」 */
    private int version;

    private MemoryStore(Path projectFile, Path userFile) {
        this.projectFile = projectFile;
        this.userFile = userFile;
    }

    /**
     * 打开两级记忆。真实实现里项目级在仓库的 .claude 下、用户级在 home 下；
     * 这里用环境变量指目录，是为了让仓库自带一份可复现的初始记忆（假端点的剧本要用）。
     */
    public static MemoryStore open() {
        Path projectFile = Path.of(System.getenv().getOrDefault("PROJECT_MEMORY_DIR", "memory/project"), "MEMORY.md");
        Path userFile = Path.of(System.getenv().getOrDefault("USER_MEMORY_DIR", "memory/user"), "MEMORY.md");
        MemoryStore store = new MemoryStore(projectFile, userFile);
        store.load(projectFile, store.project, "project");
        store.load(userFile, store.user, "user");
        System.out.println("   [memory] 项目级 " + store.project.size() + " 条（" + projectFile + "）"
                + "，用户级 " + store.user.size() + " 条（" + userFile + "）");
        return store;
    }

    // ---------- 对外三件事：目录、读、写 ----------

    /**
     * 目录。每条一行，这是唯一常驻上下文的部分。
     *
     * 为什么不能把全文塞进 system prompt？30 条 × 150 字 = 4500 字，每一轮都在付这笔钱，
     * 而大部分条目这一轮根本用不上。索引行 20 来个字，认出来了再 memory_read。
     */
    public String indexSection() {
        String lines = index();
        if (lines.isBlank()) {
            return "\n\nLong-term memory: (empty — nothing has been remembered for this project yet)\n";
        }
        return "\n\nLong-term memory from previous sessions. Only a one-line index is loaded here — "
                + "the full text is NOT in your context. Call memory_read(key) when the work depends on the details:\n"
                + lines + "\n";
    }

    /** 目录正文：两级合并，同名 key 项目级覆盖用户级 */
    public String index() {
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Entry e : user.values()) {
            merged.put(e.key(), e);
        }
        for (Entry e : project.values()) {
            merged.put(e.key(), e);   // 项目级同名覆盖：项目约定比个人偏好更具体
        }
        if (merged.isEmpty()) {
            return "";
        }
        List<Entry> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparing(Entry::key));
        StringBuilder sb = new StringBuilder();
        for (Entry e : ordered) {
            sb.append("- ").append(e.key()).append(" [").append(e.scope()).append("] — ")
                    .append(e.digest()).append('\n');
        }
        return sb.toString().strip();
    }

    /** 读全文，顺便记一次「被用过」——淘汰策略靠这个字段 */
    public String read(String rawKey) {
        String key = normalize(rawKey);
        Entry found = project.get(key);
        Path file = projectFile;
        if (found == null) {
            found = user.get(key);
            file = userFile;
        }
        if (found == null) {
            readsMiss++;
            System.out.println("   [memory] 没命中 " + key + "（目录里没有这条）");
            return "(no memory named '" + key + "'. Known keys: " + String.join(", ", keys()) + ")";
        }
        readsHit++;
        Entry touched = found.touched(now());
        if ("project".equals(found.scope())) {
            project.put(key, touched);
        } else {
            user.put(key, touched);
        }
        save(file, "project".equals(found.scope()) ? project : user);
        System.out.println("   [memory] 读 " + key + "（" + found.scope() + "，第 "
                + touched.reads() + " 次被读，" + found.text().length() + " 字符）");
        return "<memory key=\"" + key + "\" scope=\"" + found.scope() + "\" updated=\"" + found.updated() + "\">\n"
                + found.text() + "\n</memory>";
    }

    /**
     * 写一条。返回给模型看的文案——拒绝也是一种结果，模型要能自己改。
     *
     * 注意参数里没有 path。路径是 MemoryStore 自己拼的，模型只能选 project / user 两个 scope。
     * 这不是洁癖：write_file 有越界检查，自定义工具没有——一旦接受路径参数，
     * 就等于给模型开了一个绕过 s03 那道闸门的后门。
     */
    public String write(String scope, String rawKey, String text) {
        String key = normalize(rawKey);
        String where = "user".equals(scope) ? "user" : "project";

        if (!key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            writesRejected++;
            return reject("key 只能用小写字母、数字和连字符，2-40 个字符，收到 '" + rawKey + "'");
        }
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            writesRejected++;
            return reject("正文是空的");
        }
        if (body.length() > MAX_BODY) {
            writesRejected++;
            return reject("正文 " + body.length() + " 字符，超过上限 " + MAX_BODY
                    + "。记忆存的是一句可复用的事实，不是过程记录：把结论压成一句话再写，"
                    + "过程留在代码和提交历史里");
        }

        Map<String, Entry> target = "user".equals(where) ? user : project;
        Path file = "user".equals(where) ? userFile : projectFile;
        Entry old = target.get(key);
        target.put(key, old == null
                ? new Entry(key, where, body, now(), now(), 0)
                : old.rewritten(body, now()));
        writesAccepted++;
        version++;

        String evicted = null;
        while (target.size() > MAX_ENTRIES) {
            evicted = evictOldest(target);
        }
        save(file, target);

        System.out.println("   [memory] 写 " + key + "（" + where + (old == null ? "，新增" : "，覆盖旧值")
                + "，" + body.length() + " 字符）" + (evicted == null ? "" : "，淘汰 " + evicted));
        return "✓ memory " + key + " saved (" + where + ", " + body.length() + " chars"
                + (old == null ? "" : ", replaced the previous value") + ")"
                + (evicted == null ? "" : "; evicted the least recently read entry: " + evicted);
    }

    /** 收尾统计：目录多大、全文多大——这对数字就是「索引常驻」的理由 */
    public void report() {
        int lines = index().isBlank() ? 0 : index().split("\n").length;
        int indexTokens = Compactor.tokens(index());
        StringBuilder all = new StringBuilder();
        for (Entry e : project.values()) {
            all.append(e.text());
        }
        for (Entry e : user.values()) {
            all.append(e.text());
        }
        int bodyTokens = Compactor.tokens(all.toString());
        System.out.println("\n   [memory] 磁盘上 " + (project.size() + user.size()) + " 条：目录 "
                + lines + " 行 ≈ " + indexTokens + " token，全文合计 ≈ " + bodyTokens + " token");
        System.out.println("   [memory] 本会话：读 " + (readsHit + readsMiss) + " 次（命中 " + readsHit
                + "，未命中 " + readsMiss + "），写 " + (writesAccepted + writesRejected) + " 次（采纳 "
                + writesAccepted + "，拒绝 " + writesRejected + "）");
    }

    public Map<String, Entry> projectEntries() {
        return project;
    }

    /** 目录版本号：变了说明有一条记忆被写过，system prompt 该重拼了 */
    public int version() {
        return version;
    }

    // ---------- tool schema ----------

    public ObjectNode writeSchema(ObjectMapper mapper) {
        ObjectNode props = mapper.createObjectNode();

        ObjectNode scope = mapper.createObjectNode();
        scope.put("type", "string");
        scope.put("description", "project = facts about this repository (shared with the team); "
                + "user = preferences that follow the person across projects");
        ArrayNode scopes = mapper.createArrayNode();
        scopes.add("project");
        scopes.add("user");
        scope.set("enum", scopes);
        props.set("scope", scope);

        ObjectNode key = mapper.createObjectNode();
        key.put("type", "string");
        key.put("description", "Short stable slug, lowercase letters/digits/hyphens, e.g. api-error-shape. "
                + "Same key overwrites the previous value.");
        props.set("key", key);

        ObjectNode text = mapper.createObjectNode();
        text.put("type", "string");
        text.put("description", "One reusable fact, at most " + MAX_BODY + " characters. "
                + "Not a log, not file contents, not a list of everything you did.");
        props.set("text", text);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("scope").add("key").add("text"));
        return schema;
    }

    public ObjectNode readSchema(ObjectMapper mapper) {
        ObjectNode props = mapper.createObjectNode();
        ObjectNode key = mapper.createObjectNode();
        key.put("type", "string");
        key.put("description", "Key exactly as shown in the memory index in the system prompt");
        props.set("key", key);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("key"));
        return schema;
    }

    /** 目录里能查到的所有 key——memory_read 找不到时回一条候选，省一轮 */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        if (index().isBlank()) {
            return out;
        }
        for (String line : index().split("\n")) {
            int end = line.indexOf(" [");
            if (end > 2) {
                out.add(line.substring(2, end));
            }
        }
        return out;
    }

    // ---------- 文件读写 ----------

    private void load(Path file, Map<String, Entry> into, String scope) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String key = null;
            String updated = "";
            String lastRead = "";
            int reads = 0;
            StringBuilder body = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("## ")) {
                    flush(into, scope, key, updated, lastRead, reads, body);
                    key = line.substring(3).strip();
                    updated = "";
                    lastRead = "";
                    reads = 0;
                    body.setLength(0);
                } else if (line.startsWith("updated: ")) {
                    updated = line.substring(9).strip();
                } else if (line.startsWith("last-read: ")) {
                    lastRead = line.substring(11).strip();
                } else if (line.startsWith("reads: ")) {
                    reads = Integer.parseInt(line.substring(7).strip());
                } else if (key != null) {
                    body.append(line).append('\n');
                }
            }
            flush(into, scope, key, updated, lastRead, reads, body);
        } catch (IOException e) {
            System.out.println("   [memory] 读不了 " + file + "：" + e.getMessage());
        }
    }

    private void flush(Map<String, Entry> into, String scope, String key, String updated,
                       String lastRead, int reads, StringBuilder body) {
        if (key == null || key.isBlank()) {
            return;
        }
        String text = body.toString().strip();
        if (text.isEmpty()) {
            return;
        }
        into.put(key, new Entry(key, scope, text, updated, lastRead.isBlank() ? updated : lastRead, reads));
    }

    private void save(Path file, Map<String, Entry> entries) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# MEMORY · ").append(file.getParent().getFileName()).append('\n');
            sb.append("# 一条一个事实。正文超过 ").append(MAX_BODY)
                    .append(" 字符会被拒；同名 key 覆盖旧值；超过 ").append(MAX_ENTRIES)
                    .append(" 条淘汰最久没读的。\n");
            for (Entry e : entries.values()) {
                sb.append("\n## ").append(e.key()).append('\n');
                sb.append("updated: ").append(e.updated()).append('\n');
                sb.append("last-read: ").append(e.lastRead()).append('\n');
                sb.append("reads: ").append(e.reads()).append('\n');
                sb.append(e.text()).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("   [memory] 写不了 " + file + "：" + e.getMessage());
        }
    }

    /** 淘汰最久没被读过的。从没读过（lastRead 为空或等于 updated）的按 updated 排 */
    private String evictOldest(Map<String, Entry> entries) {
        Entry victim = null;
        for (Entry e : entries.values()) {
            if (victim == null || e.lastRead().compareTo(victim.lastRead()) < 0) {
                victim = e;
            }
        }
        if (victim == null) {
            return null;
        }
        entries.remove(victim.key());
        return victim.key();
    }

    private String reject(String why) {
        System.out.println("   [memory] ⛔ 拒绝写入：" + why);
        return "✗ memory NOT saved: " + why;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase();
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
