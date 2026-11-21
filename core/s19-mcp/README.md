# s19 · MCP Tools

s02 说「加一个工具 = 往表里注册一次」。十八篇下来，这句话一直有个隐含前提：
**工具的实现和循环在同一个 JVM 里**。

这一篇把实现搬出去：工具在另一个进程、另一种语言里，中间只隔着 stdin/stdout 上的 JSON-RPC。

## 问题

前面每一章加工具，改的都是同一处：在 `AgentLoop` 的构造函数里 `registry.register(...)` 一次。
schema 手写，handler 是 lambda，工具和宿主一起编译、一起发布。

真要做成一个能被别人用的工具，问题就来了：

- 你写了一个查内部知识库的工具，同事的 Python agent 也想用，得照着抄一遍
- 工具要访问公司内网的数据库，你不想把凭证塞进 agent 的进程
- 工具是别人维护的，一周发一版，你的 agent 不该跟着重新编译
- 一门语言写所有工具不现实：PDF 解析在 Python 生态里，风控模型在 Go 里

MCP（Model Context Protocol）回答的就是这件事：把工具搬到进程外，用一套固定协议描述和调用。
消息格式是 JSON-RPC 2.0，传输方式是「一行一条 JSON」，其余全是约定。

这一篇只做一件事：**手写这个协议的客户端，把远端工具接进 s02 那张表。**

## 相对 s12 的改动

| 文件 | 改了什么 |
|---|---|
| `mcp-server.py` | 新增。最小 MCP server：四个方法、三个工具，每个请求一个线程 |
| `McpClient.java` | 新增。起进程、按行读写、id 配对、超时、重连 |
| `McpTools.java` | 新增。握手 → `tools/list` → 注册进 `ToolRegistry`；调用的结果拼 text |
| `AgentLoop.java` | 构造函数末尾多一行 `McpTools.install(registry, mapper)`；`report()` 多打一行 |
| `fake-endpoint.py` | 重写。剧本换成 MCP 调用，中途 kill server，最后调一个永远不返回的工具 |

循环本身一行没改。它仍然只认 `registry.dispatch(name, args)`。

## 三个方法

握手、列表、调用。MCP 的常用面就这三条，加一条没有响应的通知。

```
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
     "protocolVersion":"2024-11-05","capabilities":{},
     "clientInfo":{"name":"lcc-s19","version":"1.0.0"}}}
← {"jsonrpc":"2.0","id":1,"result":{
     "protocolVersion":"2024-11-05",
     "capabilities":{"tools":{"listChanged":false}},
     "serverInfo":{"name":"demo-mcp","version":"0.1.0"}}}

→ {"jsonrpc":"2.0","method":"notifications/initialized"}
```

`notifications/initialized` 没有 `id`，也没有响应。协议规定握手完必须发一条，表示「我这边准备好了」。
少了它，严格的 server 会拒绝后面的请求。

```
→ {"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}
← {"jsonrpc":"2.0","id":4,"result":{"tools":[
     {"name":"add","description":"Add two numbers and return the sum.",
      "inputSchema":{"type":"object",
        "properties":{"a":{"type":"number"},"b":{"type":"number"}},
        "required":["a","b"]}}, ...]}}
```

```
→ {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
     "name":"add","arguments":{"a":2,"b":3}}}
← {"jsonrpc":"2.0","id":5,"result":{
     "content":[{"type":"text","text":"5"}],"isError":false}}
```

`content` 是数组，因为它可以同时装文本、图片、资源链接。这一篇只拼 `type == "text"` 的那些。

`isError` 是「工具执行失败」，不是「协议失败」。协议失败走 JSON-RPC 的 `error` 字段
（比如 `-32602 unknown tool`）。两者要分开：前者应该回灌给模型，后者说明客户端用错了协议。

## 名字为什么是三段

注册进本地表的名字是 `mcp__demo__echo`，不是 `echo`。

- 两个 server 都提供 `echo` 时不会互相覆盖
- 本地也有一批工具，将来某个本地工具正好叫 `echo` 也不冲突
- 模型从名字就能看出这不是本机文件操作
- 看日志里一行 `→ mcp__demo__echo(...)`，就知道该去哪个进程翻

`schema` 原样透传：server 给的 `inputSchema` 直接当 OpenAI 的 `parameters` 用。
中间不翻译，就不会因为翻译丢掉 `required`、`enum`、嵌套对象这些字段。

实测里端点验了这一点：`mcp__demo__add` 的 `parameters.properties.a.type` 是 `number`，
`required` 是 `["a","b"]`，和 server 返回的一字不差。

## id 配对：不能假设一问一答

最直觉的客户端写法是「写一条、读一行、当成这条的响应」。在这个 server 上会立刻出错。

`mcp-server.py` 每个带 id 的请求开一个线程。`echo` 收到 `"SLOW"` 会睡 450ms，
先后发两条时，后发的 `add` 先回，日志里是这样：

```
recv {"id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"SLOW"}}}
recv {"id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":20,"b":22}}}
send {"id":3,"result":{"content":[{"type":"text","text":"42"}],"isError":false}}
send {"id":2,"result":{"content":[{"type":"text","text":"slow-ok"}],"isError":false}}
```

所以客户端维护一张 `Map<Integer, CompletableFuture>`：

```java
Pending p = new Pending(id, method);
pending.put(id, p);
writeLine(msg);
// 读线程收到响应时
Pending p = pending.remove(msg.get("id").asInt());
if (p != null) p.future.complete(msg);
```

启动时跑一次自检（`McpClient.selfCheck`），同时发 `SLOW` 和 `add(20,22)`，
断言两条各自拿到 `slow-ok` 和 `42`。这不是演示，是防止以后有人把这段改回同步写法：

```
   [mcp] 乱序自检通过：id 3 先回 42，id 2 后回 slow-ok
```

迟到的响应也处理了：超时那一条已经从 `pending` 删掉，响应回来时找不到主人，
打印一行「丢弃迟到的响应」，不能把它当成新请求的答案。

## 超时和「进程死了」是两件事

```java
JsonNode msg = p.future.get(timeoutMs, TimeUnit.MILLISECONDS);
```

默认 2000ms（`MCP_TIMEOUT_MS`）。没有这个上限，一个卡住的 server 会让整个 agent 永远挂着——
而且不是报错，是看起来还在工作。

超时之后先看进程还活着没有：

```java
if (!alive) throw new McpDead("等了 " + timeoutMs + "ms 没有响应，进程已退出");
throw new McpTimeout("等 " + p.method + " 的响应超时（" + timeoutMs + "ms）", timeoutMs);
```

- `McpTimeout`：对面慢。重试只是再等一个超时，所以直接回一条错误给模型，不重试。
- `McpDead`：管道断了。这种情况下同一个连接再发一万次也没用，得重连。

混成一个异常的写法，在 server 被杀掉时会先白等 2 秒，然后报「超时」，
把「进程没了」说成「对面慢」——排查的人会去查性能，而真正的问题是进程死了。

## 重连

```java
if (attempt == 2) return "(error: mcp server is down: " + d.getMessage() + ")";
client.restart();   // 重新起进程 + 重新握手
```

重连只做一次。工具表不重新拉：server 还是同一个，工具不会凭空长出来。
`id` 也不归零——日志里能分出重连前后，乱序断言也不会被重复的 id 骗过去。

有一个容易漏的竞态：旧进程的读线程结束时会把 `alive` 置成 `false`。
如果它比新进程启动得晚，就会把新连接标成死的。所以每次 `launch()` 递增一个 `generation`，
读线程只在 `gen == generation` 时才做收尾。

实测里 kill 之后的表现：

```
   [mcp] 重连第 1 次（旧进程已不在）
   [mcp] 启动 python3 mcp-server.py（pid 87878）
   [mcp] initialize → protocolVersion=2024-11-05，server=demo-mcp/0.1.0
7
```

## isError 回到 s11 的约定

```java
return result.path("isError").asBoolean(false) ? "(error: " + text + ")" : text;
```

`(error: ...)` 是前面章节一直在用的形状，`AgentLoop.looksLikeError` 认它，
包成 `<tool-error tool="...">` 再进上下文。

于是远端工具失败和本地工具失败，对模型来说是同一种东西：

```
→ mcp__demo__fail({})
<tool-error tool="mcp__demo__fail">(error: boom: disk on fire (simulated))
这次调用失败了。按错误信息改参数或换工具，别把同样的调用再发一次。</tool-error>
```

这一层不统一，模型就得学两套错误格式，而它只会照着你给的样子学。

## 实测

`SCENARIO=main`，7 轮，端口 8919，超时 2000ms：

```
   [mcp] 启动 python3 mcp-server.py（pid 87868）
   [mcp] initialize → protocolVersion=2024-11-05，server=demo-mcp/0.1.0
   [mcp] 乱序自检通过：id 3 先回 42，id 2 后回 slow-ok
   [mcp] 已接入 demo：3 个工具 mcp__demo__echo, mcp__demo__add, mcp__demo__fail

→ mcp__demo__add({"a":2,"b":3})
5

→ mcp__demo__echo({"text":"hello from mcp"})
hello from mcp

→ mcp__demo__fail({})
<tool-error tool="mcp__demo__fail">(error: boom: disk on fire (simulated)) ...

   [fake] 杀掉 MCP server pid=87868

→ mcp__demo__add({"a":3,"b":4})
   [mcp] 重连第 1 次（旧进程已不在）
7

→ mcp__demo__echo({"text":"HANG"})
<tool-error tool="mcp__demo__echo">(error: mcp timeout 2000ms calling echo) ...

   [mcp] server=demo，重连 1 次，超时 1 次，单次上限 2000ms
```

16 项断言，全 YES：

```
initialize_version_correct=YES       客户端说的协议版本是 2024-11-05
client_info_present=YES              clientInfo=lcc-s19/1.0.0
initialized_notification_sent=YES    initialize 之后发了一条没有 id 的通知
tools_list_three=YES                 tools/list 回 3 个：echo,add,fail
tools_prefixed_in_request=YES        发给模型的 tools 里有 mcp__demo__echo/add/fail
schema_passed_through=YES            add 的 inputSchema 原样到了 parameters
call_add_result_five=YES             add(2,3) 的 tool 消息是 '5'
call_echo_text_joined=YES            content[] 里的 text 被拼成一个字符串
iserror_mapped_to_tool_error=YES     isError=true 变成 <tool-error tool="mcp__demo__fail">
unknown_tool_explicit_error=YES      没静默成功，报的是 unknown tool
out_of_order_responses_paired=YES    id 3 先回、id 2 后回，两条各自配对
reconnected_after_kill=YES           新进程 87878，并重新 initialize
reconnect_call_succeeded=YES         重连之后 add(3,4) 回了 '7'
timeout_returns_error=YES            HANG 的工具回了 mcp timeout 2000ms
timeout_cut_at_two_seconds=YES       卡住到下一次 POST 之间 2.02s
loop_survived=YES                    剧本 7 轮全部走完，客户端没崩
```

`timeout_cut_at_two_seconds` 测的是「卡住那轮请求」到「下一次请求」的间隔，2.02s。
断言写成 `1.9 <= x <= 4.5`，因为调度和 JVM 启动都有抖动，写等值跑两次就会红。

## 这一篇没解决的

- **只有 stdio 传输**。真实 MCP 还支持 HTTP + SSE，那种要处理断线重连和会话 id，这里只做进程内管道。
- **只处理文本结果**。`content[]` 里的图片、`resource_link`、内嵌资源全被丢掉了。
- **不理会 `tools/list_changed`**。server 说工具表变了，这里的注册表不会跟着变，得重启 agent。
- **重连后工具表不刷新**。server 换了版本、多了工具，客户端看不见。
- **并发没有上限**。`tools/call` 是同步等的；多工具并行调用要线程池配超时，这一篇没做。
- **没有鉴权**。stdio 靠进程边界，HTTP 传输那一套 token 校验完全没有。
- **server 挂了，那一轮的工具调用只能失败一次**。重连成功也只是把这一次调用补回来，前面失败的那次已经回灌给模型了。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| MCP 传输 | stdio，一行一条 JSON-RPC |
| 协议版本 | `2024-11-05` |
| 客户端 | `lcc-s19` / `1.0.0` |
| 单次调用超时 | `MCP_TIMEOUT_MS=2000` |
| server | `python3 mcp-server.py`，3 个工具（echo / add / fail） |
| 故障注入 | 第 5 轮 SIGKILL server；第 6 轮调用一个睡 30s 的工具 |
| 剧本 | 7 轮：add / echo / fail / 不存在的工具 / kill 后 add / HANG / 收尾 |

## 跑一遍

```sh
cd build-claude-code-java/core/s19-mcp
SCENARIO=main python3 fake-endpoint.py &

MCP_SERVER="python3 mcp-server.py" MCP_LOG=/tmp/s19_mcp.log MCP_TIMEOUT_MS=2000 \
  OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8919/v1 \
  MODEL_ID=fake mvn -q exec:java | tee /tmp/s19_stdout.log

cat /tmp/s19_assert.txt
```

不设 `MCP_SERVER` 时启动日志是 `[mcp] 没设 MCP_SERVER，只用本地工具`，
其余能力（任务、记忆、技能、压缩）和 s12 完全一样。
