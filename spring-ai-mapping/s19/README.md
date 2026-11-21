# s19 对照 · Spring AI 的 MCP client

同一个 `mcp-server.py`，Spring AI 里怎么接。对照 `core/s19-mcp` 手写的 `McpClient` + `McpTools`。

## 解决什么问题

手写版为了接一个 server，写了三样东西：起进程和读写管道（`McpClient.launch` / `readLoop`）、
握手和工具发现（`handshake` / `install` 里的 `tools/list`）、调用结果的转换（`render`）。

Spring AI 有官方 MCP client starter。这一对照回答：这些活里，框架接走了哪些，
剩下的部分和手写版怎么一一对上。

## 验证到什么程度

| 项 | 状态 |
|---|---|
| 依赖解析（`spring-ai-starter-mcp-client` 2.1.0） | ✅ 本机联网拉通 |
| `mvn clean compile` | ✅ 通过 |
| 启动时连上 `python3 mcp-server.py` | ✅ 日志：`Server response with Protocol: 2024-11-05 … Info: Implementation[name=demo-mcp, version=0.1.0]`，随后 `[mcp] 远端工具 3 个：[echo, add, fail]` |
| MCP 工具进 ChatClient 的 tools 数组 | ✅ 本地假模型收到的 `tools= ['echo', 'add', 'fail']` |
| 模型真的发起 `tools/call` | ✅ 2026-11-21 用 DeepSeek `deepseek-chat` 真跑：模型一次并发调了 `add(2,3)` 和 `echo("hello from mcp")`，返回 `5` 与 `hello from mcp`，最终回答把两个结果都念了出来 |
| 多 server 同名工具 | ❌ 没验。见下面「和手写版的一处差异」 |
| `request-timeout: 2s` 在 call 阶段是否生效 | ❌ 没验。超时路径没有专门注入故障 |
| `request-timeout: 2s` 在**握手阶段**够不够 | ❌ 不够，但原因不是 2 秒 |

关于最后一行，踩到一个坑值得记：`mvn spring-boot:run` 会 fork 一个 JVM，那个 JVM 的
工作目录是**模块目录**而不是你敲命令的目录。`application.yml` 里写的是相对路径
`args: [mcp-server.py]`，于是 server 进程起在 `spring-ai-mapping/s19/` 下、找不到文件，
握手永远等不到响应——2 秒超时、把超时调到 15 秒，报的都是同一个
`TimeoutException: Did not observe any item or terminal signal within 15000ms`。
看上去像超时太短，其实是根本没起起来。

把工作目录指对就跑通了：

```sh
cd build-claude-code-java
OPENAI_API_KEY=... OPENAI_BASE_URL=https://api.deepseek.com MODEL_ID=deepseek-chat \
mvn -f spring-ai-mapping/s19/pom.xml spring-boot:run \
  -Dspring-boot.run.workingDirectory="$PWD/core/s19-mcp"
```

启动耗时 1.368 秒，握手正常。手写版没这个问题，因为它自己 `ProcessBuilder.directory(...)`
指定了工作目录——框架把「起进程」接走了，但没接走「在哪儿起」这件事。

⚠️ 这个命令跑完**不会自己退出**：MCP client 的 stdio 连接占着非守护线程，主逻辑打完之后
JVM 还在等。日志里出现 `=== 最终回答 ===` 就是跑完了，手动 `Ctrl+C`，或者用
`timeout 60 ...` 包一层。挂着的进程会一直占着你终端和那个 `python3 mcp-server.py` 子进程。

## 手写版做了什么

```
ProcessBuilder 起进程 + 两个读线程
initialize（protocolVersion / clientInfo）→ notifications/initialized
tools/list → 每个工具 registry.register("mcp__demo__" + name, ...)
调用：写一行 JSON-RPC，pending 表按 id 等响应，2s 超时
进程死了：restart() 重起进程 + 重新握手，重发一次
```

## 这里怎么写

`pom.xml` 加一个 starter：

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

`application.yml` 写连接：

```yaml
spring:
  ai:
    mcp:
      client:
        request-timeout: 2s
        stdio:
          connections:
            demo:
              command: python3
              args: [mcp-server.py]
```

代码里只剩两件事：拿 `ToolCallbackProvider` 交给 ChatClient，需要的时候注入 `McpSyncClient` 看工具：

```java
for (McpSyncClient client : mcpClients) {
    McpSchema.ListToolsResult listed = client.listTools();
    System.out.println("[mcp] 远端工具 " + listed.tools().size() + " 个："
            + listed.tools().stream().map(McpSchema.Tool::name).toList());
}
ChatClient chat = builder.defaultTools(mcpToolProvider).build();
```

对应关系：

| 手写版 | Spring AI 2.1 |
|---|---|
| `ProcessBuilder` + stdin/stdout 读写线程 | starter 的 `StdioClientTransport` |
| `initialize` + `notifications/initialized` | 建连时自动做 |
| `pending` 表 + `CompletableFuture` 按 id 配对 | `McpSyncClient` 内部 |
| `MCP_TIMEOUT_MS` | `spring.ai.mcp.client.request-timeout` |
| `tools/list` → `registry.register` | `SyncMcpToolCallbackProvider` 自动注册 |
| `render()` 拼 `content[]` / 转 `isError` | 框架转成 `ToolCallback` 的返回值 |
| `restart()` 重连 | 没找到对应配置，进程死了要自己处理 |

## 和手写版的一处差异

实测发现：Spring AI 把 server 的工具名**原样**放进 tools 数组。本地假模型收到的是
`['echo', 'add', 'fail']`，没有 `mcp__demo__` 前缀。

这意味着两个 server 都有 `echo` 时，后注册的会覆盖先注册的——正是手写版加三段前缀要避免的。
接多个 server 之前，得自己确认框架版本有没有提供命名空间配置，或者在 server 侧把工具名写开。

## 关键差异

手写版你能看见 `pending`、看见 `generation`、看见超时抛哪个异常。这里全在框架里。

生产里该用框架。但没手写过一遍，`request-timeout` 到底作用在握手还是每一次 `tools/call`、
进程死了之后 `McpSyncClient` 会不会自己重连，这些问题读文档也未必有答案。
`core/s19-mcp` 存在的意义就是把这些问题先在三十行代码里撞一遍。

## 跑法

```sh
# server 的工作目录要在 core/s19-mcp，或者把 args 改成绝对路径
cd build-claude-code-java/spring-ai-mapping/s19
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com
export MODEL_ID=deepseek-chat
mvn -q spring-boot:run
```

依赖：Spring AI 2.1.0，Spring Boot 4.2.0，JDK 21。

> 版本备注：Spring AI 2.1.0（2026-11-20 GA）给 MCP 加了 2026-07-28（「modern」）协议——无 initialize、无会话，客户端能力随每个请求带，服务端用 `spring.ai.mcp.server.protocol=MODERN` 开。本模块实测（2.1.0，默认协议）走的是 2024-11-05，握手日志里的 `Protocol` 行对应它。
`mcp-server.py` 需要 python3，且在 `core/s19-mcp` 下能找到。
