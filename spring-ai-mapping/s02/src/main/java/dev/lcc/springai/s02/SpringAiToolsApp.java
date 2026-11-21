package dev.lcc.springai.s02;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * s02 的 Spring AI 对照实现。
 *
 * 对比 core/s02-tools 手写的 ToolRegistry 分发表：
 *
 * 手写版要做的三件事，这里全被 @Tool 注解和框架包办了：
 *   1. 写 schema（ToolRegistry.register 的 parameters）  -> @Tool / @ToolParam 自动生成
 *   2. 建 name -> handler 的 Map（dispatch 查表）        -> 框架反射扫描 @Tool 方法
 *   3. 解析 arguments、调用、结果回传                    -> ToolCallingManager + advisor
 *
 * 手写版加一个工具 = register 一次（schema + handler 绑一起）。
 * 这里加一个工具 = 写一个 @Tool 方法。两边都是「加一处」，但框架那处你看不见它怎么查表。
 *
 * 这正是对照的意义：看懂手写的 Map 查表，你才知道 @Tool 背后发生了什么。
 */
@SpringBootApplication
public class SpringAiToolsApp {

    @Bean
    CommandLineRunner demo(ChatClient.Builder builder) {
        return args -> {
            ChatClient chatClient = builder.build();

            String answer = chatClient
                    .prompt("Read README.md and tell me what this project is about. "
                            + "Also list all .java files in the current directory.")
                    // 5 个工具一次注册——框架扫描 @Tool 方法，等价于往分发表注册 5 次
                    .tools(new BuiltinTools())
                    .call()
                    .content();

            System.out.println("\n=== 最终回答 ===\n" + answer);
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(SpringAiToolsApp.class, args);
    }

    /** 5 个工具方法，每个 @Tool = 手写版的一次 register */
    public static class BuiltinTools {

        @Tool(description = "Run a shell command in the current working directory")
        public String bash(String command) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
                pb.redirectErrorStream(true);  // stderr 也回传给模型
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                }
                p.waitFor();
                return out.toString().strip();
            } catch (Exception e) {
                return "(error: " + e.getMessage() + ")";
            }
        }

        @Tool(description = "Read a UTF-8 text file, optionally only the first N lines")
        public String readFile(
                @ToolParam(description = "File path relative to the working directory") String path,
                @ToolParam(description = "Optional max number of lines to read", required = false) Integer limit) {
            try {
                List<String> lines = Files.readAllLines(Path.of(path));
                if (limit != null && limit > 0 && lines.size() > limit) {
                    lines = lines.subList(0, limit);
                }
                return String.join("\n", lines);
            } catch (Exception e) {
                return "(error: " + e.getMessage() + ")";
            }
        }

        @Tool(description = "Write UTF-8 content to a file (creates or overwrites)")
        public String writeFile(
                @ToolParam(description = "File path relative to the working directory") String path,
                @ToolParam(description = "Content to write") String content) {
            try {
                Path p = Path.of(path);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, content);
                return "Wrote " + content.length() + " chars to " + path;
            } catch (Exception e) {
                return "(error: " + e.getMessage() + ")";
            }
        }

        @Tool(description = "Replace the first occurrence of old_text with new_text in a file")
        public String editFile(
                @ToolParam(description = "File path relative to the working directory") String path,
                @ToolParam(description = "Text to find") String oldText,
                @ToolParam(description = "Replacement text") String newText) {
            try {
                Path p = Path.of(path);
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

        @Tool(description = "Find files matching a glob pattern, e.g. **/*.java")
        public String glob(@ToolParam(description = "Glob pattern to match") String pattern) {
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
    }
}
