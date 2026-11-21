# s08 · Context Compact 上下文压缩

## 解决什么问题

前面几篇都在想办法「少放点东西进 `messages`」：子 Agent 只回结论（s06），技能正文按需加载（s07）。但已经进去的东西不会自己出来——读过的文件、跑过的命令、加载过的技能正文，全都留在数组里。

一个正经任务读十几份文件很常见，上下文就是这么满的。等到满了，API 直接拒绝，任务中断，前面几十轮白跑。

`s08` 做的是前面都没做过的一件事：**把 `messages` 中间那段删掉，用一段摘要顶替**。删的地方要对：切点必须落在工具调用组的边界上，原始目标和最近几轮不能动，压缩之后「还在生效的状态」要重新贴回去。

## 相对 s07 的变更

| 组件 | s07 | s08 |
| --- | --- | --- |
| 工具数 | 8 | 8（不变） |
| 新代码 | — | `Compactor`：估算体积、找切点、生成摘要、重建数组 |
| 循环 | `while(true)` | 同一个循环，开头多一行 `compactor.maybeCompact(messages)` |
| hook | 4 个 | 4 个（不变） |
| `TodoStore` | 只打印看板 | 多一个 `board()`，压缩后拿它回贴 |

循环里就多这一行：

```java
while (true) {
    // s08：进模型之前先看一眼体积。超了就把中间那段换成摘要
    compactor.maybeCompact(messages);
    ...
}
```

「上下文管理」听起来像架构问题，落到代码上就是 `messages` 数组在进模型之前被改了一次。

## 四个设计决策

1. **切点只能落在工具调用组的边界上** —— OpenAI 协议要求每条 `role=tool` 消息都能找到带对应 `tool_call_id` 的 assistant 消息。切断这两者，下一次请求直接 400。所以找切点时先找「回合结束」（assistant 且不带 `tool_calls`），找不到就退到「非 tool 消息」。
2. **原始目标和最近两轮永远不动** —— 摘要会漂。第一次压完还剩八成信息，压到第五次就只剩一句「在做日志分析」。目标留在原位，摘要只负责中间过程。
3. **压得太少就不压** —— 压一次要调一次模型，成本固定。只压掉两条消息却生成 400 字摘要，净亏。所以有 `MIN_DROP = 6`，不够就等下一轮。
4. **活状态重新贴，别指望摘要** —— 待办清单每轮都在变，摘要是某一刻的快照。技能正文更麻烦：它很可能正好被压掉，模型会以为细则还在。所以压缩后用 `<live-state>` 把「当前待办 + 已加载技能名」重新贴一遍。

## 压缩长什么样

```
<summary>
（前 6 条消息已压缩移除，原上下文约 3072 token）
目标=挑出最慢的三条 SQL 给索引建议；已完成=读过 slow-1..slow-3，最慢的是 /api/orders 的 4200ms；
下一步=读剩下两份日志，写 notes/verdict.txt
</summary>

<live-state>
当前待办清单：
[✓] 读 data 下五份慢查询日志
[▸] 挑出最慢的三条 SQL
[ ] 按 sql-review 规范给索引建议
[ ] 结论写到 notes/verdict.txt
Progress: 1/4 complete
已加载技能：sql-review（技能正文如已不在上下文里，重新调 skill(name) 取回）
</live-state>

继续当前任务。摘要里写「已完成」的不要重做；需要细节就重新读文件或重新取技能。
```

摘要本身是一次普通的模型调用：不带工具表，`system` 里放压缩指令 + 原始目标，`user` 里放要丢掉那段的纯文本。工具结果进摘要请求前先截断到 600 字符——不然摘要请求和原文一样大，白忙。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 接口 | OpenAI 兼容：`OPENAI_BASE_URL` / `OPENAI_API_KEY` |
| temperature | 未显式设置（用服务端默认） |
| 上下文预算 | `CONTEXT_LIMIT` 环境变量，默认 8000；触发线 = 预算 × 0.75 |
| 保留尾巴 | 4 条消息（两轮工具调用） |
| 一次最少压 | 6 条 |
| 体积估算 | 汉字算 1、英文单词算 1，粗估，不接 tokenizer |
| max_tokens | 8000 |

## 怎么跑

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可换任意兼容端点
mvn -q clean compile
mvn -q exec:java
```

默认任务是「把 data 下那几份慢查询日志过一遍，挑出最慢的三条 SQL，按规范给索引建议」。真实模型跑的话，日志得自己准备；想验证机制而不花钱，用仓库里的假端点：

```sh
python3 fake-endpoint.py &        # 会顺手生成 data/slow-1..5.log
CONTEXT_LIMIT=3000 OPENAI_API_KEY=sk-fake \
  OPENAI_BASE_URL=http://127.0.0.1:8908/v1 MODEL_ID=fake-model mvn -q exec:java
```

把预算压到 3000，是为了让五份日志能在十几轮内顶到触发线。

## 验证输出（假端点，实际执行）

```
→ todo_write([4 tasks])
→ skill("sql-review")
   [skill] 载入 sql-review：正文 340 chars，预授权 [read_file]
→ read_file({"path":"data/slow-1.log"})
→ read_file({"path":"data/slow-2.log"})
→ read_file({"path":"data/slow-3.log"})
   [compact] 第 1 次压缩：6 条 → 1 条摘要，上下文 3072 → 2157 token（估算），摘要 113 字符
→ read_file({"path":"data/slow-4.log"})
   [compact] 上下文 2976 token 已超预算，但能压的只有 4 条（< 6），这轮先不压
→ read_file({"path":"data/slow-5.log"})
   [compact] 第 2 次压缩：6 条 → 1 条摘要，上下文 3795 → 2142 token（估算），摘要 113 字符
→ todo_write([4 tasks])
→ write_file({"path":"notes/verdict.txt",...})
   [compact] 第 3 次压缩：6 条 → 1 条摘要，上下文 2275 → 622 token（估算），摘要 113 字符
   [HOOK] Stop: 本会话共 9 次工具调用

日志过完了，最慢的三条 SQL 和索引建议已写到 notes/verdict.txt。

   [compact] 本会话压缩 3 次，累计省下 4221 token（估算）
```

十四个断言：

```
summary_request_has_goal=YES     摘要请求锚定了原始目标
summary_request_clipped=YES      工具结果先截断再送摘要
summary_request_no_tools=YES     摘要调用不带工具表，只能回文本
summary_in_context=YES           摘要正文顶替了被删的那段
goal_still_first=YES             原始目标还在第 2 条
live_state_reinjected=YES        <live-state> 把待办清单贴回去了
loaded_skill_reminder=YES        技能正文被压掉时提醒重新取
recent_kept=YES                  最近一轮工具结果原样保留
size_shrunk=YES                  压缩后确实变小
tail_not_started_by_tool=YES     摘要后面没有孤立的 tool 消息
summary_recursive=YES            第二次压缩的输入里带着第一次的摘要
tool_call_pairing_intact=YES     13 个请求，0 次孤立 tool 消息
compaction_happened=YES          至少压过一次
summary_requests_at_least_2=YES  压了 3 次
```

## 把边界判断关掉会怎样

`Compactor` 里留了个开关，`CUT_MODE=naive` 时直接切在「尾巴之前」，不做边界判断：

```sh
CUT_MODE=naive CONTEXT_LIMIT=3000 ... mvn -q exec:java
```

同一套剧本跑下来：**12 个请求里有 5 个带着孤立的 `tool` 消息**。本地假端点只是打一行日志，真实 API 会在这些请求上返回 400，整个任务中断——而且是在压缩生效之后才断，前面的活儿全白干。

这就是「压缩」和「删几条消息」的区别。

## 简化说明

- 体积是粗估（汉字/英文单词），不接真 tokenizer。压缩只需要判断「是不是快满了」，不需要精确到个位。
- 摘要只生成一次，不做校验。真实实现会检查摘要是否漏了目标、是否比原文还长。
- 没有处理「单条工具结果本身就超预算」的情况。压缩救不了这个——得在工具层面截断，属于另一条路。
- 摘要会一层层套娃：第二次压缩会把第一次的摘要再压一遍，细节逐层丢失。所以重要结论要落到文件里，别指望上下文记得住。
- 触发是每轮同步检查，没有后台线程，也没有「压到刚好」的分档策略。
