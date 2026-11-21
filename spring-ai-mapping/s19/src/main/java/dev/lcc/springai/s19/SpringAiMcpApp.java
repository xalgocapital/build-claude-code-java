package dev.lcc.springai.s19;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * s19 的 Spring AI 2.0 对照：用官方 MCP client 接同一个 mcp-server.py。
 *
 * 手写版（core/s19-mcp）里，下面这些全是自己写的：
 *   ProcessBuilder 起进程 / 两个读线程 / pending 表按 id 配对 /
 *   initialize + notifications/initialized / tools/list 之后 registry.register /
 *   MCP_TIMEOUT_MS 超时 / 进程死了重连。
 *
 * 这里只剩两件事：
 *   1. application.yml 里写一行启动命令，starter 建连接、做握手
 *   2. ToolCallbackProvider 交给 ChatClient，MCP 工具自动进工具表
 *
 * 本机没有把 spring-ai-starter-mcp-client 的依赖拉下来，所以这个文件没有编译过。
 * README 里列了需要什么、对应关系是什么、哪些地方可能跟 2.0.0 的实际 API 有偏差。
 */
@SpringBootApplication
public class SpringAiMcpApp {

    @Bean
    CommandLineRunner demo(ChatClient.Builder builder,
                           ToolCallbackProvider mcpToolProvider,
                           List<McpSyncClient> mcpClients) {
        return args -> {
            // 和手写版 McpTools.install 里那个 for 循环对应：看看远端有什么
            for (McpSyncClient client : mcpClients) {
                McpSchema.ListToolsResult listed = client.listTools();
                System.out.println("[mcp] 远端工具 " + listed.tools().size() + " 个："
                        + listed.tools().stream().map(McpSchema.Tool::name).toList());
            }

            // 手写版是 registry.register("mcp__demo__" + name, ...)，这里由 provider 统一注册
            ChatClient chat = builder.defaultTools(mcpToolProvider).build();

            String answer = chat.prompt("用远端工具算 2+3，再让它 echo 一句 hello from mcp。")
                    .call()
                    .content();
            System.out.println("\n=== 最终回答 ===\n" + answer);
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(SpringAiMcpApp.class, args);
    }
}
