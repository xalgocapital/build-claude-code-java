# s06 · Subagent 子代理

## 解决什么问题

Agent 追踪一个调用链，读了 30 个文件、聊了 60 轮，messages 涨到 120 条。其中 110 条是「追踪」的中间过程，跟「修好这个 bug」无关。它们占着上下文位置，把最初的目标越挤越远。

`s06` 给主 Agent 一个 `task` 工具：把这类子任务丢给一个**全新上下文**的 Agent 去做，它自己跑几十轮，结束后只交回一句结论。中间过程随它自己的 messages 一起消失，而它对文件系统的改动留在工作目录里。

代码上只多了一个 `register` 调用——子代理不是循环的新分支，只是又一个 handler。

## 相对 s05 的变更

| 组件 | s05 | s06 |
| --- | --- | --- |
| 工具数 | 6 | 7（+ `task`） |
| 新代码 | — | `Subagent`：独立 `messages[]` + 30 轮上限 + 只回传文本 |
| 上下文 | 全在主对话 | 子 Agent 用全新 `messages[]` |
| 循环 | 不变 | 不变，`dispatch` 不变 |
| 权限 | PreToolUse hook | 不变，子 Agent 的工具调用照样过同一套 hook |

四条设计决策：

1. **上下文隔离** — 子 Agent 从 `[system, 任务描述]` 开始，父对话一条都不带。
2. **只回传结论** — 跑完取最后一条文本，子 Agent 的 `messages` 整个丢掉。
3. **禁止递归** — 子 Agent 的工具表只有 5 个基础工具，没有 `task`，也没有 `todo_write`。
4. **门禁不跳过** — 上下文隔离不等于权限隔离，子 Agent 的每次工具调用都过 `PreToolUse` hook。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 接口 | OpenAI 兼容：`OPENAI_BASE_URL` / `OPENAI_API_KEY` |
| temperature | 未显式设置（用服务端默认） |
| 固定提示词 | `AgentLoop.SYSTEM`（主）、`Subagent.SUB_SYSTEM`（子），见源码 |
| 子 Agent 轮数上限 | 30 |

## 怎么跑

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可换任意兼容端点
mvn -q clean compile
mvn -q exec:java
```

也可以用本地假端点跑（不花钱，验证机制而非模型质量）：

```sh
python3 /tmp/fake_llm_s06.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8906/v1 MODEL_ID=fake-model mvn -q exec:java
```

自定义任务：

```sh
mvn -q exec:java -Dexec.args="Delegate: read all .java files in src and summarize what each does"
```

## 验证输出（假端点，实际执行）

```
→ task("Find what testing framework this project uses and report the name.")
   [HOOK] PreToolUse: task()
   [Subagent spawned] Find what testing framework this project uses and report the…
   [sub] → bash({"command":"sudo rm -rf /"})
   [HOOK] permissionHook: ⛔ 命中硬拒绝 'rm -rf /'
   [sub] → bash({"command":"echo SUB-INTERMEDIATE-MARKER"})
   [HOOK] PreToolUse: bash()
   [Subagent done] 跑了 2 轮，回传 32 chars 结论
结论：测试框架是 JUnit 5（junit-jupiter）。
   [HOOK] Stop: 本会话共 2 次工具调用

main done | isolated=YES | no_recursive_task=YES | conclusion_only=YES
         | no_leak=YES | sub_denied_seen=YES | sub_ran_tools=YES
```

六个断言分别对应：子 Agent 只收到 2 条消息、子工具表里没有 `task`、主 Agent 只拿到结论、中间标记没漏进主上下文、越权命令在子 Agent 里同样被拦、子 Agent 确实执行了工具。

## 简化说明

- 只做**一种**子 Agent 模式（全新 `messages[]`）。真实 Claude Code 有三种：Normal / Fork / General-Purpose。Fork 模式不为隔离，而为让父子 Agent 的 system prompt、tools、messages 前缀字节级一致，命中 prompt cache。
- 递归防护用「不给 `task` 工具」简化，真实实现用 `isInForkChild()` 检查对话历史里的 fork 标记。
- 上下文隔离的粒度也简化了：真实实现里 `readFileState` 会从父 Agent 克隆（避免重复读同一个文件），`abortController` 父子传播，`queryTracking` 带 `depth = parentDepth + 1`。
- 只做同步（父等子跑完）。异步子 Agent（`run_in_background: true` 返回 `async_launched`，完成后通知父 Agent）留到后面。
- 子 Agent 的权限弹窗不冒泡（真实 Fork 模式 `permissionMode: 'bubble'`，用户在父终端里审批）。
