# s17 · Autonomous Agents

s16 的团队成员是被推着走的。`team.tick()` 里一个 for 循环，每个活着的成员走一步；
lead 不调这一行，整个团队就停在原地，谁也不会自己开工。

被推着走不是缺点，是当时唯一能测的写法：一次 tick 一次 HTTP，时序是确定的。
但它把「下一步做什么」这个决定留给了调用方。这一篇把它交给驱动自己。

给一个目标，它每一轮自己挑活、干活、记账，做到自己认为可以停为止。

## 问题

`team.tick()` 这一行遮住了三件事：

| 写法 | 当时为什么可以 | 什么时候不行 |
|---|---|---|
| 谁走一步由 tick 决定 | 时序确定，好测 | 没人 tick 就没人干活，「自主」无从谈起 |
| 干完就完事 | 成员只回一句结论 | 结论说完了，盘上一个字节没变，这叫没干 |
| 什么时候收工由人看 | 人盯着日志决定 | 让它自己跑，没人盯，它就永远不认为该停 |

第三条是这一篇真正的题目。**能一直跑不难，难的是停下来**。

## 相对 s16 的改动

| 文件 | 改了什么 |
|---|---|
| `Autonomy.java` | 新增。挑活 / 干活 / 记账 / 停滞判据 / 两个预算 / 自检打回，全在这里 |
| `TaskStore.java` | `Task` 加 `priority`；`create` 加带优先级的重载；`update` 加 note；看板行带 `p5` |
| `AgentLoop.java` | 注册 `autonomy_start` / `autonomy_status` / `autonomy_stop`；`task_create` 加 priority；策略段加一段 |

s16 的信封、配对、死信、handoff 全部保留，一行没删。这一篇只是多了一个入口，
那个入口进去之后不归主循环管。

## 挑活：priority 大的先做，一样大按 id

`Task` 多一个字段，落盘就多一行：

```json
{"id":"T2","subject":"补 T2 的记录：orders 表索引变更","priority":5,
 "status":"PENDING","blockedBy":[],"owner":"","note":""}
```

排序只有一条规则，写在 `Autonomy.BY_PRIORITY` 里：

```java
Comparator.comparingInt(TaskStore.Task::priority).reversed()
        .thenComparingInt(t -> idNum(t.id()));
```

`idNum` 把 `T2` 里的 2 抠出来比，不是比字符串——`T10` 和 `T2` 按字符串排会把 10 排在前面。

候选池是两拨人：`tasks.ready()`（PENDING 且前置全完成）加上「owner 是我、还挂在
IN_PROGRESS」的（上一轮被自检打回的那些）。每轮开头把整个池子按顺序打出来：

```
[autonomy] R1 候选 4 条：T2(p5) T3(p5) T1(p2) T4(p1) → 挑 T2
[autonomy] R2 候选 3 条：T3(p5) T1(p2) T4(p1) → 挑 T3
[autonomy] R3 候选 2 条：T1(p2) T4(p1) → 挑 T1
```

第三行值得看一眼：T1 卡住之后，它优先级 2，比剩下的 T4（p1）高，所以它一直挡在前面。
实测里 T4 一次都没被挑到。这不是 bug，是优先级排队的必然结果——也是自主驱动最难看的地方：
**一条卡住的活会把整条队伍堵死**。

## 干活：一个什么都不知道的会话

每条任务开一份全新的 messages：一条 system，一条 user。system 里只有三样东西——
目标、这一条任务、验收命令。没有别的任务，没有上一轮为什么没过（除了写在任务 note 上的那句），
没有驱动的记账。

实测断言里有一条专门查这个：worker 第 1 次 POST 只有 2 条 messages，
system 里不含 lead 的身份串。上下文隔离是「这一轮的进展归谁」的前提，
混在一起就分不清是驱动的判断还是 worker 的动作。

worker 的工具是全套 5 个（`bash` / `read_file` / `write_file` / `edit_file` / `glob`），
每次调用照样过 `PreToolUse`。自主不等于免检，写越界还是拒。

worker 最多走 6 步（`AUTONOMY_WORKER_STEPS`）。它自己说完了就提前结束，说不完就到这里为止，
结论记成「走满 6 步没有给结论」，照样进自检。

## 记账：三个信号，都写在代码里

这一轮到底有没有往前挪，看三样东西：

| 信号 | 怎么算 | 为什么是它 |
|---|---|---|
| 任务状态 | `status != statusBefore` | PENDING→COMPLETED 是硬事实 |
| 任务 note | `!note.equals(noteBefore)` | 自检的结论落在 note 上，变了就是新信息 |
| 工作目录 | mtime 快照，路径 → 大小@毫秒 | 文件真被改了 |

快照是 `Files.walk(notes/)` 每个文件取 `size@mtime`，两轮比一遍，差几个就是几个：

```java
out.put(root.relativize(p).toString(),
        Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
```

只比文件名不够——worker 完全可以把同一个文件重写一遍，什么都没改也叫「动了」。
加上大小，改一个字的概率就低得多。要精确到内容得存哈希，那是每轮重读所有文件，这里不值。

三个信号一个都没动，就是这一轮什么都没有：

```
[autonomy] R4 T1 记账：状态 IN_PROGRESS→IN_PROGRESS 未变，快照 ±0，note 没变 → 无进展（连续 1 轮）
```

## 收敛判据：连续 3 轮没动就停

```java
if (!NAIVE && stall >= STALL_ROUNDS) {
    stopReason = "stall";
    System.out.println("   [autonomy] 连续 " + STALL_ROUNDS + " 轮无进展，停在 R" + next
            + "（第 " + next + " 轮没有开始）");
    break;
}
```

三件事必须说清楚。

**第一，为什么不让模型自己判断。** 你问它「做完了吗」，它永远有话说：还在确认、还在分析、
再给我一轮。这不是它在骗人，是它的输出里没有「没进展」这个状态——它只知道自己想了什么，
不知道盘上变了什么。所以判据必须读盘。

**第二，为什么是 3 轮。** 一轮可能只是读文件，两轮可能还在想，三轮没落地就是没落地。
`AUTONOMY_STALL_ROUNDS` 可改，默认 3。

**第三，自检失败算不算进展。** 算，但只算第一次。第一次失败带来的是新信息
（任务从 PENDING 变成 IN_PROGRESS，note 从空变成一句具体原因），第二次同样的失败什么都没带来。
这一条不这么写，卡住的任务会靠「每轮都失败」永远算有进展，一直跑到 `MAX_ROUNDS`——
那这一篇的核心判据就等于没有。实测里 R3 是第一次失败（有进展），R4/R5/R6 是同样的失败（无进展）。

### 和 s11 的 LoopDetector 不是一回事

s11 那个检测器看的是「同一个工具调用连着重复」：

```java
String fp = name + "|" + canonical(args);   // 粒度：一次调用
```

第 3 次提醒，第 5 次中止。它抓的是原地打转：读同一个不存在的文件、跑同一条失败命令。

这一篇看的是「这一轮有没有把目标往前推」：

```
粒度：一整轮（挑活 + 干活 + 自检 + 记账）
```

它抓的是「一直在忙但什么都没变」——每轮工具调用都不一样，参数也不一样，worker 每次都能
给出新的一段结论，只有盘上一动不动。

两种都得有。LoopDetector 拦不住「换个说法重试」，停滞判据拦不住「一次调用死循环了 5 遍」。
一个在调用层，一个在轮次层。

## 预算：两个上限，谁先到算谁

```java
if (round >= MAX_ROUNDS)   { stopReason = "rounds"; ... }
if (tokens >= MAX_TOKENS)  { stopReason = "tokens"; ... }
```

回合上限 12，token 上限 60000。token 是估的，不是账单：

```java
// 中日韩字符按 1 token，其余按 4 字符 1 token
return cjk + (other + 3) / 4;
```

估高了会白等一轮，估低了会多花一点钱。这里选估高。

两个上限都在轮次开头看，所以停下来的那一轮一定没有半截状态：任务板要么是上一轮收完的样子，
要么是这一轮收完的样子。

实测三种停法都跑到了：

```
默认：      停：stall｜跑了 6 轮，估算 3542 tokens
MAX_ROUNDS=5：停：rounds｜跑了 5 轮，估算 2909 tokens
MAX_TOKENS=700：停：tokens｜跑了 2 轮，估算 1073 tokens
```

## 自检：打回，不改状态

任务标 COMPLETED 之前跑一次 `VERIFY_CMD`（默认 `true`，等于不验）。跑在工作目录里，
任务 id 走环境变量传进去，所以一个命令能验所有任务：

```sh
VERIFY_CMD='test -s "$AUTONOMY_TASK_ID.md" || { echo "$AUTONOMY_TASK_ID.md 不存在或是空的"; exit 1; }'
```

失败就往回打，原因写进 note：

```
[autonomy] R3 T1 自检：exit 1，失败（test -s "$AUTONOMY_TASK_ID.md" || …）
[autonomy] R3 T1 打回 IN_PROGRESS：自检失败（exit 1）：T1.md 不存在或是空的
```

为什么打回 IN_PROGRESS 而不是 PENDING？因为 PENDING 意味着「还没开始」，
而这条已经开过头了——worker 看过文件、想过、没落盘。下一轮再挑到它，note 上的那句话还在。

这也是 note 存在的唯一理由：驱动进程的内存活不过一轮，盘上的字活得过。

## 实测：15 次 POST，停在 R7

剧本：4 条任务（T2/T3 是 p5，T1 是 p2，T4 是 p1），驱动跑 main 场景。
16 项断言全 YES：

```
SCENARIO=main POSTS=15
priority_persisted=YES            四个任务文件都带 priority：T2/T3=5、T1=2、T4=1
pick_highest_first=YES            R1 候选 T2(p5) T3(p5) T1(p2) T4(p1)，挑走 T2
tie_broken_by_id=YES              R2 挑 T3：同为 p5，按 id 升序
candidate_order_sorted=YES        R1 候选串按 priority 降序、id 升序
worker_context_isolated=YES       worker 第 1 次 POST 只有 2 条 messages
verify_pass_completes=YES         T2/T3 盘上 COMPLETED，note「自检通过」
verify_fail_bounces=YES           T1 打回 IN_PROGRESS，owner=autonomy
bounce_note_has_reason=YES        note 里带着命令自己的输出
failed_task_repicked=YES          R4/R5/R6 又挑到 T1
first_fail_counts_as_progress=YES R3 第一次失败算有进展
stall_counts_to_three=YES         无进展的是 R4,5,6，最后一轮 streak=3
stall_stops_at_r7=YES             日志原文：连续 3 轮无进展，停在 R7
stall_reason_in_summary=YES       reason=stall，rounds=6，tokens=3542
status_shows_budget_and_streak=YES R6/12，3542/60000 还剩 56458，3/3
stop_after_finish_says_stopped=YES 停完再调 stop：它说驱动已经停了
script_finished=YES               15 次 POST：lead 3 次、worker 12 次
```

六轮的账，一行一轮：

```
R1 T2 542 tokens  状态 PENDING→COMPLETED，快照 +1 → 有进展
R2 T3 531 tokens  状态 PENDING→COMPLETED，快照 +1 → 有进展
R3 T1 570 tokens  状态 PENDING→IN_PROGRESS，快照 ±0，note 变了 → 有进展
R4 T1 633 tokens  状态未变，快照 ±0，note 没变 → 无进展（连续 1 轮）
R5 T1 633 tokens  → 无进展（连续 2 轮）
R6 T1 633 tokens  → 无进展（连续 3 轮）
R7                连续 3 轮无进展，停在 R7（第 7 轮没有开始）
```

12 次 worker POST = 6 轮 × 2 步（一次工具、一句结论）。lead 3 次：交目标、查状态、
说一句收尾的话。

`autonomy_status()` 那一行：

```
autonomy 停着｜轮次 R6/12｜tokens 3542/60000（还剩 56458）｜连续无进展 3/3 轮
｜完成 2 条，打回 4 次｜停因 stall：连续 3 轮没有进展，第 7 轮没有开始
```

打回 4 次 = R3、R4、R5、R6 各一次。完成 2 条 = T2、T3。T4 一次没被挑到。

## 反例：去掉停滞判据

`AUTONOMY_MODE=naive` 只做一件事：不检查 `stall`。两个预算还在。

```
SCENARIO=naive POSTS=27
naive_runs_to_max_rounds=YES   跑了 12 轮，main 那遍是 6 轮
naive_spins_after_round3=YES   R3 之后 9 轮全是原地转
naive_burns_more_tokens=YES    token 7340 vs main 3542，多烧 3798
naive_no_stall_line=YES        日志里没有那句「连续 3 轮无进展，停在 R…」
```

| | main | naive |
|---|---|---|
| 轮次 | 6 | 12 |
| 无进展的轮次 | 3（R4–R6） | 9（R4–R12） |
| 估算 token | 3542 | 7340 |
| POST | 15 | 27 |
| 停下来的原因 | 连续 3 轮没动 | 回合上限 12 |

naive 那 9 轮里，每一轮都长这样：挑 T1、worker 读一遍 T2.md、说一句「想不出写什么」、
自检 exit 1、note 写同一句话。工具调用不一样吗？每轮都是 `read_file`，参数也一样——
但那不重要，重要的是**盘上没有一行是新的**。

多跑 6 轮，多烧 3798 tokens，多做 0 件事。

## 这一篇没解决的

- **一条卡住的活堵死整条队**。T1（p2）挡住 T4（p1），T4 从头到尾没被挑到。真实场景该有超时降级或者让出队列。
- **没有并发**。`autonomy_start` 是同步的，调用方等它跑完。想同时跑两个目标，得先把任务板做成多线程安全的。
- **孤儿任务捡不回来**。打回的任务停在 IN_PROGRESS，进程重启就被 s12 的规则降级成 ORPHANED，`pick()` 不认这个状态。
- **没有预算的细粒度控制**。token 只在轮次边界看，一个 worker 在轮内烧掉两倍预算也拦不住。
- **自检只有一个命令**。`VERIFY_CMD` 对所有任务一样，靠任务 id 区分。真要按任务写不同的验收，得让它从任务文件里读。
- **没有「主动放弃」**。它只会停，不会说「这条我做不了，换一条」。停下来的原因是预算或停滞，不是判断。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 停滞阈值 | `AUTONOMY_STALL_ROUNDS` 默认 3 |
| 回合上限 | `AUTONOMY_MAX_ROUNDS` 默认 12 |
| token 上限 | `AUTONOMY_MAX_TOKENS` 默认 60000（估算，不是账单） |
| 工作目录 | `AUTONOMY_WORKSPACE` 默认 `notes`（快照和自检都在这里） |
| 自检命令 | `VERIFY_CMD` 默认 `true`；任务 id 走 `AUTONOMY_TASK_ID` |
| worker 步数 | `AUTONOMY_WORKER_STEPS` 默认 6 |
| 反例开关 | `AUTONOMY_MODE`：checked（默认）/ naive |
| 任务 | T1 p2、T2 p5、T3 p5、T4 p1，全部 PENDING、无依赖 |
| 剧本（main） | 6 轮 / 15 次 POST（lead 3、worker 12） |
| 剧本（rounds） | `AUTONOMY_MAX_ROUNDS=5`，5 轮 / 13 次 POST |
| 剧本（tokens） | `AUTONOMY_MAX_TOKENS=700`，2 轮 / 7 次 POST |
| 剧本（naive） | `AUTONOMY_MODE=naive`，12 轮 / 27 次 POST |

`.tasks/`、`.team/`、`notes/` 都在 `.gitignore` 里，由假端点在启动时造出来，测试产物不入库。

## 跑一遍

四遍要按顺序跑：naive 那一遍要读 main 留下的 `/tmp/s17_run_main.json` 做对比。

```sh
VC='test -s "$AUTONOMY_TASK_ID.md" || { echo "$AUTONOMY_TASK_ID.md 不存在或是空的"; exit 1; }'

SCENARIO=main python3 fake-endpoint.py &          # 端口 8917，会清空 .tasks/ notes/ 重建
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8917/v1 \
  MODEL_ID=fake VERIFY_CMD="$VC" mvn -q exec:java > /tmp/s17_stdout.log 2>&1
cat /tmp/s17_assert.txt                            # 16 项全 YES

SCENARIO=rounds python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8917/v1 \
  MODEL_ID=fake AUTONOMY_MAX_ROUNDS=5 VERIFY_CMD="$VC" mvn -q exec:java > /tmp/s17_stdout.log 2>&1
cat /tmp/s17_assert_rounds.txt                     # 2 项全 YES

SCENARIO=tokens python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8917/v1 \
  MODEL_ID=fake AUTONOMY_MAX_TOKENS=700 VERIFY_CMD="$VC" mvn -q exec:java > /tmp/s17_stdout.log 2>&1
cat /tmp/s17_assert_tokens.txt                     # 2 项全 YES

SCENARIO=naive python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8917/v1 \
  MODEL_ID=fake AUTONOMY_MODE=naive VERIFY_CMD="$VC" mvn -q exec:java > /tmp/s17_stdout.log 2>&1
cat /tmp/s17_assert_naive.txt                      # 4 项全 YES
```

每一遍都必须是「先起端点、再起 JVM」：端点要在第一次请求之前把 `.tasks/` 和 `notes/` 清干净。
JVM 的 stdout 必须落在端点读的那个文件上（`STDOUT_LOG`，默认 `/tmp/s17_stdout.log`），
不然端点等不到结束标记，断言全是空跑出来的。
