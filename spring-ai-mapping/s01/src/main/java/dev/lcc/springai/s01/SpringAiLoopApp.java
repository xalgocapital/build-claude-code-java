package dev.lcc.springai.s01;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * s01 的 Spring AI 2.0 对照实现。
 *
 * 对比 core/s01-loop 的手写 AgentLoop：
 *
 * 手写版要自己写的东西，这里全被框架做了：
 *   - while(true) 循环        -> ToolCallingAdvisor（递归 advisor，重入链）
 *   - 工具定义 JSON Schema    -> @Tool 注解
 *   - tool_calls 解析执行     -> ToolCallingManager
 *   - 结果回传 messages       -> advisor 内部维护 conversation history
 *
 * Spring AI 2.0 的关键变化：工具循环从 ChatModel 内部被提升到 advisor 链，
 * ChatClient 自动注册 ToolCallingAdvisor，模型要调工具就重入链，直到不调为止。
 *
 * 一句话：框架把那 30 行包成了你看不见的一层。看懂 core 手写版，
 * 你才知道这里发生了什么——这就是对照的意义。
 */
@SpringBootApplication
public class SpringAiLoopApp {

    @Bean
    CommandLineRunner demo(ChatClient.Builder builder) {
        return args -> {
            ChatClient chatClient = builder.build();

            String answer = chatClient
                    .prompt("List the files in the current directory, then tell me how many there are.")
                    .tools(new BashTools())
                    .call()
                    .content();

            System.out.println("\n=== 最终回答 ===\n" + answer);
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(SpringAiLoopApp.class, args);
    }

    /** @Tool 自动生成 JSON Schema，替代手写 tools 数组 */
    public static class BashTools {
        @org.springframework.ai.tool.annotation.Tool(
                description = "Run a shell command in the current working directory")
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
    }
}
