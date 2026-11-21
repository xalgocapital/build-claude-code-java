package dev.lcc.s18;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 5 个内置工具的实现。
 *
 * s01 只有 bash：读文件 cat、写文件 echo >、改文件 sed——模型要拼 shell 命令。
 * s02 把最常用的四个操作提成专用工具，模型想「读文件」就说 read_file，不用再拼 cat。
 *
 * s18：每个工具都绑一个 base 目录。s02 起这些函数用的是 Path.of(".")，
 * 也就是 JVM 的进程工作目录——所有人一份，谁都改不了。成员要各写各的树，
 * 就得让「当前目录」变成注册工具时传进去的参数，而不是进程的全局状态。
 *
 * 这件事必须在代码里做。写进提示词（「你只能改 .worktrees/alice 下的文件」）只是建议，
 * 模型手一滑写 notes/plan.md，落的还是主树，而且没人报错。绑在 base 上，它连主树的路径都拼不出来：
 * safePath 把相对路径钉死在 base 里，`../` 越不出去。
 */
public final class BuiltinTools {
    private BuiltinTools() {}

    /** 把 5 个工具一次性注册进分发表，全部钉在当前进程的工作目录 */
    public static void install(ToolRegistry reg) {
        install(reg, Path.of(".").toAbsolutePath().normalize());
    }

    /** s18：钉在指定的目录上。主循环用上面那个，成员用这个 */
    public static void install(ToolRegistry reg, Path base) {
        Path root = base.toAbsolutePath().normalize();
        registerBash(reg, root);
        registerReadFile(reg, root);
        registerWriteFile(reg, root);
        registerEditFile(reg, root);
        registerGlob(reg, root);
    }

    /**
     * s15：只装读的两个。
     *
     * 成员的职责是「去查清楚一件事」，不是「顺手改掉」。这里不是权限问题——
     * 是真的会出事：三个人同时改同一个文件，谁改坏的说不清，也没人知道该以谁为准。
     * 要写就报给 lead，让一个人写。
     *
     * s18 不再对成员用这个。原因不是「现在安全了」，是「出事以后能查出来」：
     * 每人一棵树，改动先落在自己那棵上，合不合由 merge 决定。
     */
    public static void installReadOnly(ToolRegistry reg) {
        installReadOnly(reg, Path.of(".").toAbsolutePath().normalize());
    }

    public static void installReadOnly(ToolRegistry reg, Path base) {
        Path root = base.toAbsolutePath().normalize();
        registerReadFile(reg, root);
        registerGlob(reg, root);
    }

    private static void registerBash(ToolRegistry reg, Path base) {
        ObjectMapper m = reg.mapper();
        reg.register("bash",
                "Run a shell command in the working directory (" + base + ").",
                params(m, p(m, "command", "string", "The shell command to execute")),
                args -> runBash(args.path("command").asText(""), base));
    }

    private static void registerReadFile(ToolRegistry reg, Path base) {
        ObjectMapper m = reg.mapper();
        reg.register("read_file",
                "Read a UTF-8 text file, optionally only the first N lines. "
                        + "Path is relative to the working directory (" + base + ").",
                params(m,
                        p(m, "path", "string", "File path relative to the working directory"),
                        p(m, "limit", "integer", "Optional max number of lines to read")),
                args -> runRead(
                        args.path("path").asText(""),
                        args.has("limit") ? args.path("limit").asInt() : 0, base));
    }

    private static void registerWriteFile(ToolRegistry reg, Path base) {
        ObjectMapper m = reg.mapper();
        reg.register("write_file",
                "Write UTF-8 content to a file (creates or overwrites). "
                        + "Path is relative to the working directory (" + base + ").",
                params(m,
                        p(m, "path", "string", "File path relative to the working directory"),
                        p(m, "content", "string", "Content to write")),
                args -> runWrite(
                        args.path("path").asText(""),
                        args.path("content").asText(""), base));
    }

    private static void registerEditFile(ToolRegistry reg, Path base) {
        ObjectMapper m = reg.mapper();
        reg.register("edit_file",
                "Replace the first occurrence of old_text with new_text in a file. "
                        + "Path is relative to the working directory (" + base + ").",
                params(m,
                        p(m, "path", "string", "File path relative to the working directory"),
                        p(m, "old_text", "string", "Text to find"),
                        p(m, "new_text", "string", "Replacement text")),
                args -> runEdit(
                        args.path("path").asText(""),
                        args.path("old_text").asText(""),
                        args.path("new_text").asText(""), base));
    }

    private static void registerGlob(ToolRegistry reg, Path base) {
        ObjectMapper m = reg.mapper();
        reg.register("glob",
                "Find files matching a glob pattern, e.g. **/*.java. Searched under " + base + ".",
                params(m, p(m, "pattern", "string", "Glob pattern to match")),
                args -> runGlob(args.path("pattern").asText(""), base));
    }

    // ---------- 工具实现 ----------

    private static String runBash(String command, Path base) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(base.toFile());
            pb.redirectErrorStream(true);  // stderr 也回传给模型，否则报错信息看不见
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "(command timed out)";
            }
            return out.toString().strip();
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String runRead(String path, int limit, Path base) {
        try {
            Path p = safePath(path, base);
            List<String> lines = Files.readAllLines(p);
            if (limit > 0 && lines.size() > limit) {
                lines = lines.subList(0, limit);
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String runWrite(String path, String content, Path base) {
        try {
            Path p = safePath(path, base);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "Wrote " + content.length() + " chars to " + path;
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String runEdit(String path, String oldText, String newText, Path base) {
        try {
            Path p = safePath(path, base);
            String text = Files.readString(p);
            if (!text.contains(oldText)) {
                return "Error: text not found";
            }
            Files.writeString(p, text.replace(oldText, newText));
            return "Edited " + path;
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String runGlob(String pattern, Path base) {
        try {
            Path root = base.toAbsolutePath().normalize();
            var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> hits = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .map(root::relativize)
                        .filter(matcher::matches)
                        .sorted()
                        .forEach(x -> hits.add(x.toString()));
            }
            return String.join("\n", hits);
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    /**
     * 路径安全：所有 file 工具的限制都在这里。
     * 把模型给的相对路径钉死在 base 里，../ 越不出去。
     * s18 起 base 是参数：成员的 base 是它自己的 worktree，越不出去就等于碰不到主树。
     * bash 不走这里——所以 rm 照跑，靠的是 PreToolUse 那道门。
     */
    private static Path safePath(String rel, Path base) throws IOException {
        Path root = base.toAbsolutePath().normalize();
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) {
            throw new IOException("path escapes working directory: " + rel);
        }
        return p;
    }

    // ---------- schema 构造小工具 ----------

    private static ObjectNode p(ObjectMapper m, String name, String type, String desc) {
        ObjectNode prop = m.createObjectNode();
        prop.put("type", type);
        prop.put("description", desc);
        ObjectNode wrapper = m.createObjectNode();
        wrapper.set(name, prop);
        return wrapper;
    }

    private static ObjectNode params(ObjectMapper m, ObjectNode... props) {
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = m.createObjectNode();
        ArrayNode required = m.createArrayNode();
        for (ObjectNode prop : props) {
            prop.fields().forEachRemaining(e -> {
                properties.set(e.getKey(), e.getValue());
                // description 以 Optional 开头的参数不进 required（如 read_file 的 limit）
                String desc = e.getValue().path("description").asText("");
                if (!desc.startsWith("Optional")) {
                    required.add(e.getKey());
                }
            });
        }
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }
}
