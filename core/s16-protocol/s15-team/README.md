# s15 · Agent Teams

s06 的子代理是一个函数：`task(description)` 进去，一句结论出来，中间的三十轮全部扔掉。
它解决的是「别让探索过程污染我的上下文」。它解决不了另一件事：

两件互不相干的活要同时查，我只想知道结果，不想自己按顺序做完两遍。

## 问题

s06 那个 `Subagent.run()` 有三处藏不住：

| 写法 | 当时为什么可以 | 什么时候不行 |
|---|---|---|
| 跑完就 return | 只要一句结论 | 中途想改它的方向，没有入口 |
| 上下文扔掉 | 结论之外的东西都不需要 | 它记不住自己上一步做了什么 |
| 工具表里没有 `task` | 防递归 | 它也没法跟任何别的代理说话 |

第三条不只是「不能递归」。**代理之间没有通道**，就意味着不能被纠正、不能汇报中间状态、
不能在任务被抢时换个做法。s12 把任务挪到了盘上，可盘上只有任务，没有人。

## 相对 s12 的改动

| 文件 | 改了什么 |
|---|---|
| `Mailbox.java` | 新增。一人一个 `inbox.jsonl` + 一个 `cursor` |
| `Teammate.java` | 新增。有名字、有历史、有收件箱的会话，一步一个 `step()` |
| `Team.java` | 新增。成员表 + `spawn` / `send` / `status` / `tick` |
| `TaskStore.java` | `update()` 加认领冲突；`owner` 传空串改成「不改」；看板写出 owner |
| `BuiltinTools.java` | 拆出 `installReadOnly()`，成员只有 `read_file` / `glob` |
| `AgentLoop.java` | 注册 `team_spawn` / `send_message` / `team_status`；每轮开头一次 `team.tick()` |

s11 的错误恢复、s12 的任务落盘、s10 的分段拼装全部保留。

## 三个类，各管一件事

```
Team          成员表（LinkedHashMap，注册顺序就是 tick 顺序）
  ├─ spawn(name, role, prompt)   → new Teammate(...)
  ├─ send(from, to, text)        → mailbox.post(...)  ×N
  ├─ status()                    → 每人一行：在干什么 / 未读几条
  └─ tick()                      → 每个 ACTIVE 的成员 step() 一次

Teammate      一个会话：自己的 messages、自己的 system、自己的工具表
  └─ step()   收信 → 一次模型请求 → 执行 tool_calls → 有结论就返回

Mailbox       .team/<name>/inbox.jsonl（只追加）+ cursor（读到第几行）
```

成员共享的只有 `OpenAICompatClient` 和 `TaskStore`。工具表是各自一份，
不是权限考虑——三个人同时改一个文件，谁也说不清是谁改坏的。

`task_update` 也注册了两次。lead 那份带 `blockedBy` 重排，成员那份只有
`id` / `status` / `owner`。成员不该改依赖：它看不到全局，改一条可能让另一条永远开不了工。

## 收件箱是文件

```
.team/
  lead/
    inbox.jsonl        成员的结论投到这里
    cursor
  researcher/
    inbox.jsonl
    cursor
```

`inbox.jsonl` 一行一条，**读了也不删**：

```json
{"from":"lead","to":"researcher","at":"14:11:34","text":"T1 归你。读完把时间戳格式写清楚，别只贴日志。"}
```

删掉就没有历史了。重启之后你想知道「成员上次收到的是什么」，只能翻日志。

另存一个 `cursor` 而不是把 jsonl 改写一遍：追加是 O(1)，改写是 O(n)。
消息多了以后，每读一次就重写整个文件是最先变慢的地方。

`drain()` 返回的是「这一 tick 新到的」，不是「全部」。否则成员每一步都重读同一句话，
然后以为事情还没做完。

## 调度：轮转 tick，不是线程

```java
public List<Mailbox.Msg> tick() {
    tick++;
    for (Teammate t : members.values()) {
        if (t.state() != Teammate.State.ACTIVE) continue;
        String report = t.step();
        if (report != null) mailbox.post(t.name(), LEAD, report);
    }
    return mailbox.drain(LEAD);
}
```

三个人就是三次 HTTP 请求，串着发。没有线程池，没有 Future，没有锁。

选这个的理由只有一个：**这个系列的每一次实测都要能复现**。三个线程同时跑，
「谁先认领 T1」就由 OS 调度决定——今天的日志和明天的不一样，文章里的数字和读者
跑出来的也不一样。轮转把这件事变成注册顺序，于是「researcher 先于 indexer 抢 T1」
是可以写进断言的事实。

代价是真的：一次请求要几秒，三个成员就是三倍，lead 干等。一个成员超时，整轮都拖住。
这些写在「这一篇没解决的」里。

## 消息下一轮才看到

`tick()` 放在 `while` 的第一行：

```java
while (true) {
    for (Mailbox.Msg m : team.tick()) {                    // ① 成员先走一步
        from.put("content", "<team-inbox from=\"" + m.from() + "\">"
                + m.text() + "</team-inbox>");
        messages.add(from);                                // ② 结论进 lead 的上下文
    }
    SystemPrompt.Assembled now = buildPrompt();
    ...
    response = client.createChatCompletion(messages, tools);   // ③ 然后才轮到自己
```

顺序决定了延迟：lead 在这一轮末尾 `send_message`，成员要到下一轮开头的 tick 才会 `drain`
到它。不是网络慢，也不是缓冲区没刷——是「一步只推进一次」这件事本身。

实测里那句话在第 9 次 POST 发出，researcher 在第 10 次 POST 读到，中间没有任何
请求能提前看到它。

`post` 的返回值里特意写了这一句：

```
Queued to researcher（收件箱未读 1 条，下一 tick 才读）
```

模型最容易犯的错就是投完消息等回音。

## 认领冲突只有一个出口

两个成员看的是同一份看板，先后脚都会去抢同一条。唯一能保证「最后只有一个 owner」
的地方，是 `TaskStore.update()` 里的三行：

```java
if (!NAIVE && !owner.isBlank() && !t.owner().isBlank() && !t.owner().equals(owner)) {
    throw new IllegalStateException(id + " 已经被 " + t.owner() + " 认领");
}
```

它放在依赖检查之前：抢不到的东西，没必要先告诉它前置做完了没有。

实测：

```
   [team:researcher] → task_update(T1 → IN_PROGRESS)
   [team:researcher]   Updated T1 → IN_PROGRESS（researcher）
   [team:indexer] → task_update(T1 → IN_PROGRESS)
   [team:indexer]   (error: T1 已经被 researcher 认领)
```

顺手改了 s12 的一个坑：`owner` 传空串原本是「清空」。于是成员标一次 `COMPLETED`
就把认领记录抹掉了，认领等于没做。这一篇改成空串 = 不改。

看板也写出了认领人：

```
in progress: T3 复核两条结论（auditor）
```

不写的话，成员看到「T3 在进行中」也照样会去抢。

## team_status：谁在忙，谁的消息没人看

```
Team 3/3：
  researcher  忙（第 3 步） 未读 1 条　角色：查清 data 目录里日志的时间戳格式
  indexer     忙（第 3 步） 未读 1 条　角色：查清 db/schema.sql 里有没有联合索引
  auditor     完成          未读 1 条　角色：复核另外两个人的结论有没有矛盾
```

同一份文字也挂在 system 的 `ROUND` 档（rank 95），所以 lead 每轮开场就看得到队形。
两个出口的差别在「什么时候」：system 里那份是这一轮开头的快照；投完消息想知道
谁还没读，得再问一次工具。

那张表里有一行值得看：auditor 已经 `完成`，但收件箱还有 1 条未读。
已完成的成员不再被 tick，投给它的消息就躺在那里没人读。这一篇没有退信机制。

## 实测

主场景 6 轮 lead、5 次 tick、18 次 POST，15 项断言全 YES：

```
SCENARIO=main POSTS=18
spawn_creates_member=YES        三个成员各自发过请求：researcher 5 / indexer 4 / auditor 3 次
team_max_enforced=YES           第四个人被挡在 spawn 里，不是被提示词劝住
system_identity_distinct=YES    lead 和三个成员的 system 身份段各不相同
teammate_context_isolated=YES   researcher 的上下文里没有 schema.sql，indexer 的里没有 app.log
message_delivered_next_tick=YES 第 9 次 POST 发出，researcher 在第 10 次 POST 才读到
mailbox_on_disk=YES             .team/researcher/inbox.jsonl 里 2 行，读过也不删
claim_conflict_rejected=YES     indexer 晚一步认领 T1，被 tool 拒绝
single_owner=YES                T1 最后 owner=researcher、状态 COMPLETED
member_completed_task=YES       T2 由 indexer 认领并标完成
conclusion_back_to_lead=YES     两个成员的结论都进了 lead 的上下文
status_shows_busy=YES           广播后 researcher 还在第 3 步，auditor 已经完成
status_shows_unread=YES         三份收件箱各 1 条未读
tick_round_robin_order=YES      18 次 POST 的顺序和轮转表逐项相同
teammate_tools_readonly=YES     成员只有 glob / read_file / task_update
script_finished=YES             剧情 6 轮跑完
```

轮转那张表，逐项对得上：

```
POST  1  lead        spawn ×4（第 4 个失败）
POST  2  researcher  read_file
POST  3  indexer     read_file
POST  4  auditor     read_file
POST  5  lead        task_create ×3
POST  6  researcher  task_update      认领 T1，成功
POST  7  indexer     task_update      认领 T1，被拒
POST  8  auditor     task_update      认领 T3，成功
POST  9  lead        send_message      ← 那句话在这一轮末尾发出
POST 10  researcher  read_file         ← 到这一步才读到它
POST 11  indexer     task_update ×2    改认 T2 并标完成
POST 12  auditor     text              结论投回 lead
POST 13  lead        send_message + team_status（广播）
POST 14  researcher  task_update       T1 标完成
POST 15  indexer     text              结论投回 lead
POST 16  lead        task_list + team_status
POST 17  researcher  text              结论投回 lead
POST 18  lead        text              汇总
```

结论回传的样子（lead 第 18 次请求里的那两条 user 消息）：

```
<team-inbox from="indexer" at="14:11:34">db/schema.sql 里只有单列索引
idx_orders_user_id 和 idx_orders_status，没有联合索引。T1 被 researcher 占了，我改做 T2，已经标完成。</team-inbox>
<team-inbox from="researcher" at="14:11:34">data/app.log 的时间戳是 ISO 8601，带毫秒和
+08:00 时区，例如 2026-11-21T09:14:03.512+08:00。T1 我标完成了。</team-inbox>
```

收尾那一行：

```
   [team] 成员 3/3，累计起过 3 个，tick 5 次，投回 lead 3 条
```

## 反例：不查认领冲突

`TEAM_MODE=naive` 把那三行删掉，谁最后写谁赢：

```
naive_claim_not_rejected=YES    (没人拦：indexer 的认领直接盖掉了 researcher 的)
naive_second_claim_wins=YES     (T1 最后 owner=indexer——后来的人赢，干活的是 researcher)
naive_task_done_by_other=YES    (T1 是 COMPLETED，但 owner 已经不是做这件事的人)
```

`T1.json` 长这样：

```json
{ "id" : "T1", "status" : "COMPLETED", "owner" : "indexer" }
```

同一份剧本，checked 那一遍 owner 是 `researcher`，naive 这一遍是 `indexer`。
一条任务被两个人做过，而任务板上记的是那个没干活的人。

这种事在只有一次对话的时候不会发生。有了第二个人，它就一定会发生。

## 这一篇没解决的

- **成员之间不能直接说话**。`Mailbox.post` 不区分方向，数据通路是通的，但成员手里没有
  `send_message` 工具。谁能跟谁说、说了算不算数，是协议问题，不是投递问题。
- **tick 是串行的**。一次请求要几秒，三个人就是三倍，lead 干等。真并发要处理竞态、
  部分失败、消息乱序，这一篇用「不上线程」把它们全绕开了，也就一样没解决。
- **没有退信**。成员完成了，投给它的消息永远躺在收件箱里，未读计数一直涨。
- **没有超时**。一个成员卡在慢请求上，整轮都被它拖住；`MAX_STEPS=12` 只管「步数」，
  不管「一步多久」。
- **成员不能写文件**。这是有意的（避免同时改一个文件），但也就意味着所有写操作都回到
  lead 一个人身上，并行只在「查」这一侧成立。
- **没有身份校验**。`owner` 是字符串，成员可以填别人的名字。冲突检查只管「名字不同」，
  不管「这个名字是不是你」。
- **消息没有顺序保证**。两个成员在同一 tick 里投给 lead，谁先谁后由注册顺序决定，
  和它们的完成时间无关。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 成员上限 | `TEAM_MAX` 默认 3 |
| 收件箱目录 | `TEAM_DIR` 默认 `.team` |
| 任务目录 | `TASK_DIR` 默认 `.tasks` |
| 成员步数上限 | `MAX_STEPS=12` |
| 剧本（main） | 6 轮 lead / 5 次 tick / 18 次 POST |
| 成员步数 | researcher 5、indexer 4、auditor 3 |
| 系统提示预算 | `PROMPT_BUDGET` 默认 1200；实测首轮 1239，裁掉 `workspace` |

`.tasks/`、`.team/`、`data/`、`db/` 都在 `.gitignore` 里，`data/` 和 `db/` 由假端点在
启动时造出来，测试产物不入库。

## 跑一遍

```sh
SCENARIO=main python3 fake-endpoint.py &        # 端口 8915，会清空 .tasks/ .team/ 重建
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8915/v1 \
  MODEL_ID=fake mvn -q exec:java
cat /tmp/s15_assert.txt                          # 15 项全 YES

SCENARIO=naive python3 fake-endpoint.py &        # 反例，要配 TEAM_MODE=naive
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8915/v1 \
  MODEL_ID=fake TEAM_MODE=naive mvn -q exec:java
cat /tmp/s15_naive.txt                           # 3 项全 YES
```

两遍都必须是「先起端点、再起 JVM」：端点要在第一次请求之前把 `.tasks/` 清干净。
