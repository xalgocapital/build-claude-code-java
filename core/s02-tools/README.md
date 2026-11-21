# s02 · Tool Use（工具分发表）

给 Agent 加 5 个专用工具，循环一行不改——能力靠往一张分发表里注册来扩展。

## 解决什么问题

s01 只有 bash 一个工具。读文件要 `cat`，写文件要 `echo >`，改文件要 `sed`，找文件要 `find`。模型想的是「读这个文件」，却得拼出一条 shell 命令——多一层翻译，费 token，还容易拼错。

把常用的四个操作提成专用工具（read_file / write_file / edit_file / glob），模型说「读文件」就行。加工具的方式不是改循环，而是往分发表注册。

## 核心：一张分发表

`ToolRegistry` 就是那张表，两样东西：

- `schemas()` — 工具描述数组，发给模型，让它知道能调什么
- `dispatch(name, args)` — 按名字查表执行

加一个工具只需 `register(name, description, parameters, handler)` 一次，schema 和实现绑在一起注册，不会漏。

循环里的执行行，s01 → s02 只换了一处：

```
s01:  String out = runBash(command);              // 写死调用 bash
s02:  String out = registry.dispatch(name, args);  // 按名字查表
```

`while(true)` 那一段——调用模型、追加消息、扫 tool_calls、逐个执行、喂回——原封不动。

## 文件

```
dev/lcc/s02/
  ToolHandler.java       工具函数接口：参数 JSON -> 结果字符串
  ToolRegistry.java      分发表（本篇主角）
  BuiltinTools.java      5 个工具实现 + safePath 路径校验
  AgentLoop.java         循环（与 s01 对照，只改执行行）
  OpenAICompatClient.java  OpenAI 兼容协议薄封装（同 s01）
```

## 5 个工具

| 工具 | 作用 | 路径安全 |
|---|---|---|
| bash | 跑 shell 命令 | ❌ 不受限 |
| read_file | 读文本，可选只读前 N 行 | ✅ safePath |
| write_file | 写文件（创建/覆盖） | ✅ safePath |
| edit_file | 替换第一处文本 | ✅ safePath |
| glob | 按 pattern 找文件 | ✅（只读） |

`safePath()` 把相对路径钉死在工作目录内，`../` 越不出去。**bash 不走这道校验**——所以 s02 里 `rm -rf /` 照样能跑。这是 s03 权限机制要解决的。

## 多工具调用

模型常在一次回答里返回多个 tool_call，比如「读 a.py 和 b.py，再列出所有 .py」。我们这个实现按 `tool_calls` 的原始顺序逐个执行，结果各自作为 `role=tool` 消息追加。

真实的 Claude Code 更复杂：按连续块分批，并发安全的工具（`isConcurrencySafe`，按具体输入判断，不只是只读/写）在同一批里并行，批间严格顺序。细节见文章「深入源码」一节。

## 实验条件

| 项 | 值 |
|---|---|
| 端点 | `https://api.deepseek.com/v1`（`OPENAI_BASE_URL` 可改） |
| 模型 | `deepseek-chat`（`MODEL_ID` 可改） |
| max_tokens | 8000 |
| temperature | 服务端默认 |
| system prompt | 见 `AgentLoop.SYSTEM` |
| 固定提示词 | `Read the file README.md and tell me what this project is about.` |

> 真实 API 输出会随模型版本漂移。改动任何条件后，观察结论可能不同。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可选
export MODEL_ID=deepseek-chat                        # 可选
cd core/s02-tools
mvn -q exec:java
# 或带自己的提示词（试试让它一次读两个文件再建个汇总）
mvn -q exec:java -Dexec.args="Read README.md and pom.xml, then create a summary.txt"
```

## 观察重点

- 模型什么时候用专用工具、什么时候退回 bash
- 一个 prompt 里模型会不会一次调多个工具，顺序对不对
- 打印出的 `→ tool(args)` 能直接看到查表分发发生了什么
- 试着让它读一个 `../` 路径，看 safePath 怎么挡

## 安全

file 工具有 `safePath` 校验，bash 没有。请在临时目录运行。权限机制在 s03 加。
