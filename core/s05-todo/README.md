# s05 · TodoWrite：给 Agent 一层显式计划

## 解决什么问题

复杂任务做着做着就跑偏：工具结果不断填满上下文，最初的计划被挤出注意力，10 步重构做完 1-3 步就开始即兴发挥。

这一篇加一个 `todo_write` 工具——它**不增加任何执行能力**（不读文件、不跑命令），只让 Agent 动手前先列清单、执行中标状态。再加一个 nag reminder：连续 3 轮没更新 todo，循环自动注入一条提醒，把「更新计划」拉回模型注意力。

关键洞察：todo_write 增加的是**规划能力**，不是执行能力。

## 相对 s04 的变更

| 项 | s04 | s05 |
|---|---|---|
| 工具数 | 5 | 6（+todo_write） |
| 规划能力 | 无 | 带三态的 TODO 列表 + nag reminder |
| SYSTEM 提示 | 通用 | 加入「先列计划再标状态」引导 |
| 循环主干 | 调模型→扫 tool_calls→dispatch→回灌 | 完全不变，只多一个 roundsSinceTodo 计数器 |
| hook 结构 | 4 事件 5 hook | 原样保留 |

## 核心代码

- `TodoStore.java`：带状态的任务列表。`schema()` 生成嵌套 schema（todos 数组，每项 content + status 三态枚举）；`update()` 存内存、终端打三态看板（[ ] / ▸ / ✓ + 进度）、返回摘要给模型。每次调用提交**完整列表**覆盖旧的，不是增量打补丁。
- `AgentLoop.java`：注册第 6 个工具；`roundsSinceTodo` 计数器——本轮调了 todo_write 归零，没调则 +1，到 3 时下一轮注入 `<reminder>Update your todos...</reminder>` 并归零。

执行主干（while true）从 s01 到现在一行没动——这是这个系列一直在证明的事：能力靠往分发表注册来扩展，循环保持稳定。

## 实验条件

| 项 | 值 |
|---|---|
| 端点 | DeepSeek `https://api.deepseek.com/v1` |
| 模型 | deepseek-chat |
| max_tokens | 8000 |
| temperature | 服务端默认 |
| 固定提示词 | `Refactor 3 files: add type hints, write tests, then run them.` |
| nag 阈值 | 连续 3 轮未更新 todo |

另用本地假端点跑过端到端验证：第 1 轮调 todo_write 列 3 个 pending → 第 2/3/4 轮只干活不更新 todo → 触发一次 nag 提醒 → 第 5 轮更新为 1 done / 1 in progress / 1 pending → 收尾文字回复。确认 nag 只触发一次（注入后归零）、提醒消息确实进入 messages、arguments 对象与字符串双形态都兼容、`finish_reason` 撒谎 `tool_calls` 时靠扫空 tool_calls 正确退出。真实 API 输出会随模型版本漂移，想复现就固定上面几项。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
# 可选：换端点/模型
# export OPENAI_BASE_URL=https://api.deepseek.com/v1
# export MODEL_ID=deepseek-chat

mvn compile
mvn exec:java -Dexec.args="Refactor 3 files: add type hints, write tests, then run them."
```

观察重点：第一次工具调用是不是 todo_write？列了几步？干了几轮后 nag 触发？状态有没有从 pending 变成 in_progress / completed？

## 和真实 Claude Code 的差距

真实 CC 有两套任务系统并存：TodoWrite（V1，内存列表，退出即清空——和我们这个实现一样）和 Task System（V2，文件持久化 + blockedBy 依赖图 + 并发锁 + 四个独立工具，那是 s12 的主题）。切换由 `isTodoV2Enabled()` 控制：交互式会话默认 V2，非交互式（SDK）默认 V1。

我们省略了 `activeForm` 字段（真实 CC 用它给 UI spinner 展示「正在做什么」，我们没有 UI）。nag reminder 的「固定 3 轮」也是简化机制——真实 CC 没有这个固定轮数，更接近的是「3 个以上 todo 全完成但没有验证项时追加一条 verification 提醒」。这篇要证明的是「计划工具怎么让长任务可见、提醒怎么把注意力拉回来」，不是复刻完整的任务系统。
