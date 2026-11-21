package com.xalgocapital.claude.s04;

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
 * file 工具都过 safePath()，bash 不受限（权限是 s03 的事）。
 */
public final class BuiltinTools {
    private BuiltinTools() {}

    /** 把 5 个工具一次性注册进分发表 */
    public static void install(ToolRegistry reg) {
        ObjectMapper m = reg.mapper();

        reg.register("bash",
                "Run a shell command in the current working directory.",
                params(m, p(m, "command", "string", "The shell command to execute")),
                args -> runBash(args.path("command").asText("")));

        reg.register("read_file",
                "Read a UTF-8 text file, optionally only the first N lines.",
                params(m,
                        p(m, "path", "string", "File path relative to the working directory"),
                        p(m, "limit", "integer", "Optional max number of lines to read")),
                args -> runRead(
                        args.path("path").asText(""),
                        args.has("limit") ? args.path("limit").asInt() : 0));

        reg.register("write_file",
                "Write UTF-8 content to a file (creates or overwrites).",
                params(m,
                        p(m, "path", "string", "File path relative to the working directory"),
                        p(m, "content", "string", "Content to write")),
                args -> runWrite(
                        args.path("path").asText(""),
                        args.path("content").asText("")));

        reg.register("edit_file",
                "Replace the first occurrence of old_text with new_text in a file.",
                params(m,
                        p(m, "path", "string", "File path relative to the working directory"),
                        p(m, "old_text", "string", "Text to find"),
                        p(m, "new_text", "string", "Replacement text")),
                args -> runEdit(
                        args.path("path").asText(""),
                        args.path("old_text").asText(""),
                        args.path("new_text").asText("")));

        reg.register("glob",
                "Find files matching a glob pattern, e.g. **/*.java.",
                params(m, p(m, "pattern", "string", "Glob pattern to match")),
                args -> runGlob(args.path("pattern").asText("")));
    }

    // ---------- 工具实现 ----------

    private static String runBash(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(Path.of(".").toFile());
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

    private static String runRead(String path, int limit) {
        try {
            Path p = safePath(path);
            List<String> lines = Files.readAllLines(p);
            if (limit > 0 && lines.size() > limit) {
                lines = lines.subList(0, limit);
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String runWrite(String path, String content) {
        try {
            Path p = safePath(path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "Wrote " + content.length() + " chars to " + path;
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String runEdit(String path, String oldText, String newText) {
        try {
            Path p = safePath(path);
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

    private static String runGlob(String pattern) {
        try {
            Path root = Path.of(".").toAbsolutePath().normalize();
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
     * 把用户给的相对路径钉死在工作目录内，../ 越不出去。
     * bash 不走这里——所以 s02 里 rm -rf / 照样能跑，这是 s03 要解决的。
     */
    private static Path safePath(String rel) throws IOException {
        Path root = Path.of(".").toAbsolutePath().normalize();
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
