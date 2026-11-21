# s14 · Cron Scheduler

s13 的后台任务有个前提：模型得先说一句 `bash_bg`，任务才存在。半夜三点该跑的备份，
模型不会替你发那条命令——它也在下班。

这一篇只做一件事：**一张磁盘上的调度表，到点由守护线程自己起会话。**

## 问题

s13 的机制拆开看是「谁踢一脚」的三段依赖：

- 任务存在：模型调 `bash_bg` 才有
- 结果返回：`poll()` 在轮次边界扫一遍，塞回 messages
- 上下文：主会话的 messages，模型记得前因后果

定时任务把第一段和第三段都断了。到点触发没人踢；触发的时候主会话可能根本不在跑，
连 messages 都不存在。所以调度器必须自己带一套最小闭环：到点 → 新会话 → 落日志 → 排下一次。

## 相对 s13 的改动

| 文件 | 改了什么 |
|---|---|
| `Cron.java` | 新增。5 段表达式解析 + `next(from)` 日历推算，不引 cron 库 |
| `CronStore.java` | 新增。`.cron/jobs.json`，一条 job 八个字段，启动时读回 |
| `CronScheduler.java` | 新增。守护线程 + 调度时钟 + 触发/跳过判定，约 190 行 |
| `AgentLoop.java` | 注册 `cron_create`、`cron_list`、`cron_delete`；构造时拉起调度器。循环骨架没动 |
| `fake-endpoint.py` | 重写。端点同时扮演主会话模型和定时会话模型，靠 system 里的 `[cron]` 身份段区分 |

s13 的后台任务、增量读、通知走 messages，全部原样保留。

## 表达式：手写，不引库

五段：分 时 日 月 周。每段四种写法：`*`、`a`、`a-b`、`*/n`（步进可以叠在区间上，
逗号可以拼任意多项：`0,15,30-45/5`）。解析结果是一组 `TreeSet<Integer>`，
`next(from)` 逐分钟往后走，四年封顶（2 月 29 日最多要等四年）：

```java
public LocalDateTime next(LocalDateTime from) {
    LocalDateTime t = from.withSecond(0).withNano(0);
    for (int i = 0; i < 5 * 366 * 24 * 60; i++) {
        t = t.plusMinutes(1);
        if (matches(t)) return t;
    }
    ...
}
```

不引库是刻意的：解析本身就是这一章的一部分。一个「日」和「周」都受限时取并集的老规矩
（vixie cron 的行为，不是笔误），写对了库，你就永远不会知道有这条规矩。

两个容易写错的点，自检在启动时打出来：

```
   [cron] 自检 */5 * * * * 从 11-01 10:00 → 11-01 10:05 → 11-01 10:10 → 11-01 10:15（期望 10:05 → 10:10 → 10:15）
   [cron] 自检 0 9 * * 1-5 从周日 2026-11-22 10:00 → 周一 2026-11-23 09:00（期望一致）
```

周字段的 0 和 7 都是周日，解析时把 7 折成 0；`next(from)` 返回严格晚于 from 的点，
同一分钟内不会因为 tick 密集而触发第二次。

## 调度时钟：CRON_SCALE_MS

验证一次调度不能等真的一分钟。调度时钟是这么定义的：**真实一分钟折算成多少毫秒**，
默认 60000（不缩放）。测试设 `CRON_SCALE_MS=200`，`* * * * *` 就变成每 200ms 触发一次。
实现是一根映射：

```java
long virtualMs = realStart + (long) ((realNow - realStart) * factor);
// factor = 60000.0 / SCALE_MS，SCALE=200 时 1 秒真时 = 5 分钟调度时
```

缩放只改「时间流多快」，表达式解析和日历推算一行不改——`next()` 还是标准的
LocalDateTime 算术，jobs.json 里存的还是人能直接读的 ISO 时刻。
**这是给实验用的，生产不要开。**

tick 也定义在调度时钟里：每 `CRON_TICK_MS`（默认 500，调度时钟毫秒）醒一次，
缩放开启时折算成真时约 1ms，用 `LockSupport.parkNanos` 而不是 `Thread.sleep`——
sleep 的毫秒粒度会把 tick 拖过一整个调度分钟，凭空制造「错过」。

## 触发链：到点 → 新会话 → 落日志 → 排下一次

```java
private void run(CronStore.Job job, LocalDateTime triggerAt, int missedBefore) {
    job.lastRun = CronStore.formatTs(triggerAt);
    job.runs++;
    job.nextRun = CronStore.formatTs(job.cron.next(triggerAt));   // 排下一次

    ArrayNode messages = mapper.createArrayNode();                // 独立 messages
    // system = [cron] 身份段，user = job.prompt，就这两条
    JsonNode resp = client.createChatCompletion(messages, null);
    appendLog(job, triggerAt, content);                           // .cron/<id>.log
}
```

新会话只有 system + user 两条消息。主会话此刻在不在跑、聊到哪了，定时会话一概不知道；
反过来，定时会话的结果也不进主会话的 messages——没人在看的时候触发，结果只能落盘等人：
`.cron/<id>.log`，每次触发追加一段：

```
=== 2026-11-21T14:45:00 run#1 ===
定时任务已执行：本次触发已记录。
```

## 错过不补跑：skipped 是本章的重点

调度表在磁盘上。进程停了一夜，`jobs.json` 里的 nextRun 已经躺在十个小时前——
从那到现在的每一个触发点都错过了。补跑会在重启瞬间打出 N 个会话：一次都没成功过的
任务重试还有意义，同一件事的第 3 到第 11 次执行没有任何意义。

所以只跑一次，差额记进 `skipped`：

```java
int missed = countDue(job, due, now);   // [nextRun, now] 里共有几个触发点
run(job, now, missed - 1);              // 只按最新的一次跑
job.skipped += missed - 1;              // 差额记账，不执行
```

实测（cron_1 的 nextRun 被预置在 10 分钟前，`*/1` 从那到现在共 11 个触发点）：

```
[cron] cron_1 触发（错过 10 次，不补跑）：*/1 * * * *
```

jobs.json：`runs=13, skipped=10`。错过的 10 个点只存在于账本里。
判据是端点可见的：13 对相邻触发里只有 1 对间隔 <50ms（补跑那一发落在「现在」，
到下一个日历分钟边界的那一段天然短），不存在 11 连发。

skipped 不是「丢了」的遮羞布。它告诉下一个读表的人（人或模型）：这个任务在停机期间
欠了多少次——要不要手动补，是读表的人的决定，不是调度器的。

## 实测

8 轮主剧本（`CRON_SCALE_MS=200`，`* * * * *` 标称 200ms 一次）：

```
   [fake] 第 0 轮 → cron_create
   [fake] 第 1 轮 → cron_create
   [fake] 第 2 轮 → cron_list
   [fake] 第 3 轮 → bash
   [fake] 第 4 轮 → cron_delete
   [fake] 第 5 轮 → bash
   [fake] 第 6 轮 → cron_list
```

cron_list 的 tool 结果（模型看到的排班表）：

```
cron_1  */1 * * * *  next=2026-11-21 14:56:00  runs=2  skipped=10
    prompt: 补跑检查点：报告你被触发这件事
cron_2  */1 * * * *  next=2026-11-21 14:56:00  runs=0  skipped=0
    prompt: 心跳：报告你被触发了一次
```

十三项断言全 YES：

```
parse_step_selfcheck=YES      stdout 自检行给出 10:05 → 10:10 → 10:15
parse_weekday_selfcheck=YES   stdout 自检行：0 9 * * 1-5 从周日推到周一 09:00
no_replay_burst=YES           cron_1 的 10 对相邻触发里只有 1 对 <50ms，10 个错过的点没有被逐个补跑
missed_recorded_skipped=YES   jobs.json cron_1 skipped=10 runs=13，错过的不补
cron_sessions_isolated=YES    全部 21 次定时会话 messages 都是 2 条，主会话 query 没出现
cron_result_logged=YES        .cron/cron_1.log 与 cron_2.log 都有结果正文
heartbeat_fires=YES           cron_2 心跳触发 9 次，期望 ≥4
heartbeat_periodic=YES        cron_2 相邻间隔 165-234ms，标称 200ms
cron_list_shows_next=YES      cron_list 的 tool 结果里有 next=
cron_create_persisted=YES     jobs.json cron_2 expr=*/1 * * * * runs=11
cron_delete_stops_firing=YES  cron_3 删除前触发 1 次，删除后 0 次
main_tool_pairing_intact=YES  全部主请求 tool_call 都有回应
script_finished=YES           主剧本 8 轮跑完
```

收尾的汇总：

```
   [cron] 任务 2 条，触发 21 次，错过不补 10 次，失败 0 次
```

## 反例：补跑全部错过的周期

`CRON_MODE=naive` 把错过判定反过来：`[nextRun, now]` 里的每个点都起一个会话。
同一个预置现场（nextRun 在 10 分钟前，11 个错过点）：

```
naive_burst_count=YES          cron_1 共触发 13 次，错过的周期全补了
naive_burst_at_restart=YES     前 11 次触发集中在 54ms 内——重启瞬间
naive_skipped_zero=YES         jobs.json cron_1 skipped=0 runs=16
naive_gap_signature=YES        11 对相邻触发间隔 <50ms，主场景是 0 对
```

对比数字：

| | 主场景（不补跑） | naive（补跑） |
|---|---|---|
| 重启瞬间（前 11 次触发） | 分散在正常节奏里 | 集中在 **54ms** 内 |
| 相邻触发 <50ms 的对数 | **1**（补跑→下一个边界） | **11** |
| skipped | **10** | **0** |

补跑模式下重启瞬间就是 11 个会话排队打向模型端点。停机越久这个数越大：
停一晚上（10 小时，`*/1`）是 600 个。

## 这一篇没解决的

- **定时会话没有工具**。就是一次裸的问答，跑不了测试、改不了代码。要让它干活，
  得把 s01 的工具分发搬进 CronScheduler——循环代码会重复一份，留给下一篇的团队机制去消化。
- **失败只有一行日志**。会话请求失败记 `(run failed: ...)`，没有重试没有告警。
- **秒级表达式不支持**。cron 的老规矩就是分钟粒度，`next()` 逐分钟走也隐含了这一点。
- **没有时区**。全部用本地时区，跨时区部署的排班会错位。
- **jobs.json 没有锁文件**。两个进程同时跑调度器，会互相覆盖。
- **重启后的第一次触发落点**在「现在」而不是错过窗口里的某个点——
  语义上等价（都只执行一次），但 lastRun 记的是重启时刻，不是错过窗口里的任何一个点。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | 假端点（`MODEL_ID=fake`），主剧本 8 轮 + naive 剧本 3 轮 |
| 调度时钟 | `CRON_SCALE_MS=200`（真实 1 分钟 = 200ms；**给实验用的，生产不要开**） |
| tick | `CRON_TICK_MS=500`（调度时钟毫秒），折算真时约 1ms，parkNanos 实现 |
| 预置现场 | cron_1 `*/1 * * * *`，nextRun 预置在 10 分钟前，共 11 个错过点 |
| 目录 | `.cron/`（`CRON_DIR` 可改）：jobs.json + `<id>.log` |
| 反例开关 | `CRON_MODE=naive` |
| 断言 | 主场景 13 条 + 反例 5 条 |

间隔类数字（165-234ms、54ms）每次跑都会抖，断言写的是区间和关系
（「稳态间隔在 120-800ms」「<50ms 的对数主场景 ≤1、naive ≥8」），不是等值。
skipped=10 取决于预置现场（10 分钟整、分钟取整），是确定的。

## 跑一遍

```sh
cd build-claude-code-java/core/s14-cron
mvn -q clean compile

SCENARIO=main python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8914/v1 \
  MODEL_ID=fake CRON_SCALE_MS=200 mvn -q exec:java \
  -Dexec.args="给会话建两个定时任务：一个每分钟心跳（*/1 * * * *），一个每五分钟巡检（*/5 * * * *）；用 cron_list 确认 next，然后删掉巡检那个。"
cat /tmp/s14_assert.txt

# 反例
SCENARIO=naive ASSERT_LOG=/tmp/s14_assert_naive.txt \
  STDOUT_LOG=/tmp/s14_stdout_naive.log python3 fake-endpoint.py &
CRON_MODE=naive OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8914/v1 \
  MODEL_ID=fake CRON_SCALE_MS=200 mvn -q exec:java -Dexec.args="看看定时任务表。"
cat /tmp/s14_assert_naive.txt
```
