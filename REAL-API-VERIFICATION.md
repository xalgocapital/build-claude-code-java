# 真实模型 API 验证记录

这个仓库里每章都配了一个 `fake-endpoint.py`，它负责注入 429、半截 JSON、超长上下文、
工具抛异常这类故障，断言也写在里面。假端点能复现的是**机制**，不能证明**真实端点上也走得通**。

所以发布前补了这一轮：用真实的 OpenAI 兼容端点把每个模块跑一次。

## 怎么跑

```sh
# key 从环境变量或 /tmp/.wb_ds_key 读，不写进仓库
python3 tools/verify-real-api.py          # 全部模块
python3 tools/verify-real-api.py s11 s19  # 指定章节
```

脚本对每个模块执行 `mvn -q exec:java -Dexec.args="<query>"`，query 是一句
「不要调用任何工具，只回复四个字：验证通过」。超时 150 秒，stdin 接 `/dev/null`
（s03 的 ASK 分支在等输入，不接空会挂住）。

## 实验条件

| 项 | 值 |
|---|---|
| 日期 | 2026-11-21 |
| 端点 | `https://api.deepseek.com/v1` |
| 模型 ID | `deepseek-chat` |
| 服务端实际路由 | `deepseek-flash`（响应体 `model` 字段；`deepseek-chat` 是别名，会随服务端策略变） |
| 每模块请求数 | 1 次（不带工具调用的最小会话） |
| 单模块耗时 | 2.7–3.9 秒（含 mvn 启动） |

## 一、20 个模块的最小会话

全部 PASS。「PASS」的判据是 stdout 里出现模型回复的「验证通过」四个字，
意味着请求发出去、system 拼装完、HTTP 返回、Jackson 解析、循环收尾这一整条链路在真实端点上成立。

| 模块 | 结果 | 耗时 |
|---|---|---|
| s01-loop | PASS | 3.4s |
| s02-tools | PASS | 3.3s |
| s03-permission | PASS | 3.0s |
| s04-hooks | PASS | 3.5s |
| s05-todo | PASS | 3.5s |
| s06-subagent | PASS | 3.6s |
| s07-skill | PASS | 3.0s |
| s08-compact | PASS | 3.1s |
| s09-memory | PASS | 3.4s |
| s10-system | PASS | 3.2s |
| s11-recovery | PASS | 3.5s |
| s12-task | PASS | 3.8s |
| s13-bg | PASS | 3.9s |
| s15-team | PASS | 2.7s |
| s16-protocol | PASS | 4.7s |
| s17-auto | PASS | 5.6s |
| s18-worktree | PASS | 3.1s |
| s19-mcp | PASS | 2.7s |
| s20-agent | PASS | 3.0s |
| s14-cron | PASS | 17.8s |

## 二、工具调用链路（s02）

最小会话只验了纯文本往返，工具调用要单独验。给 s02 一句
「用 glob 工具列出当前目录下的 `*.md` 文件」：

```
→ glob({"pattern":"*.md"})
README.md

当前目录下的 `*.md` 文件只有 1 个：
- README.md
共 1 个。
```

模型自己生成了 `tool_calls`，本地 `dispatch` 执行完把结果回灌，模型据此回答。
`ToolRegistry` 那条链路（schema 进 tools 数组 → 模型选工具 → 执行 → 回灌）在真实模型上成立。

## 三、MCP 链路（s19）

MCP 走的是另一条路：工具在别人的进程里，stdin/stdout 上的 JSON-RPC。

```
→ mcp__demo__add({"a":2,"b":3})
5
[mcp] server=demo，重连 0 次，超时 0 次，单次上限 2000ms
[prompt] 本会话 3 轮：前缀复用 2 次，重算 1 次
```

握手、`tools/list`、三段命名 `mcp__demo__add`、`tools/call`、结果转文本回灌，全部走通。

## 四、Spring AI 对照模块

| 模块 | 结果 | 证据 |
|---|---|---|
| `spring-ai-mapping/s01` | PASS | 模型调用工具列出目录，返回 4 个条目 |
| `spring-ai-mapping/s02` | PASS | 模型读了 README 并总结出 `@Tool` 与 `register` 的对应关系 |
| `spring-ai-mapping/s04` | PASS | `[advisor·before]` / `[advisor·after]` 都触发，最终回答非空 |
| `spring-ai-mapping/s08` | PASS | `MessageWindowChatMemory(maxMessages=4)` 输出裁剪结果 |
| `spring-ai-mapping/s19` | PASS | 握手 `Protocol: 2024-11-05`、远端 3 个工具；模型并发调 `add(2,3)` 与 `echo("hello from mcp")`，返回 `5` 与原文，最终回答把两个都念了出来 |

> 版本备注：对照代码钉在 Spring AI 2.0.0。2.1.0 于 2026-11-20 GA（`ChatMemory` 标废弃、MCP 加 2026-07-28 协议），上表结果未在 2.1 上重跑。

## 这一轮没验的

真实的 429、503、半截 JSON、上下文超长，不可能让真实模型配合演。这些仍然只有
`fake-endpoint.py` 能测，断言也在那边。文章里引用的数字
全部来自假端点那次固定剧本，不是这一轮。

换句话说：**这一轮证明的是「代码能对接真实端点」，不是「机制在真实故障下也对」**。
后者要靠假端点，前者靠这个文件。
