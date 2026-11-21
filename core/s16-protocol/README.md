# s16 · Team Protocols

s15 的 `send_message` 是个投递函数：`post(from, to, text)`，一句话进去，落一行盘。
能传话，传不了「这是一件需要你答复的事」。

成员之间要协作，光有通道不够。谁欠谁一个答复、什么时候必须还、
一条移交算不算数——s15 一概没有答案。这一篇把消息变成信封。

## 问题

s15 那句话有三处藏不住：

| 写法 | 当时为什么可以 | 什么时候不行 |
|---|---|---|
| 消息是一句自由文本 | lead 广播任务，成员回结论 | 「帮我查 X」和「随便聊聊」长得一模一样 |
| 成员没有发信工具 | 所有结论投回 lead | 成员 A 想要成员 B 查过的东西，只能 lead 中转 |
| 移交 = 直接改 owner | s15 没有移交 | 抢和让是两回事：让要对方点头 |

第三条是 s15 认领冲突的另一面。s15 解决的是「谁先写谁赢」，
这一篇解决的是「原 owner 主动让的时候，怎么保证让出去了」。

## 相对 s15 的改动

| 文件 | 改了什么 |
|---|---|
| `Envelope.java` | 新增。`{id, from, to, type, payload, replyTo, deadline}` 四种 type |
| `Protocol.java` | 新增。配对、超时死信、移交登记，全部是代码约束 |
| `Mailbox.java` | 加 `postEnvelope()`；`Msg.render()` 按有无 type 分叉 |
| `Team.java` | `send` 委托给 Protocol；`tick()` 开头加一行 `sweep()`；status 多一行协议账 |
| `Teammate.java` | 成员拿到 `send_message` 和 `task_handoff`；`task_update` 多 ACCEPT / REJECT |
| `TaskStore.java` | 加 `transfer()`：绕过认领冲突检查的 owner 变更（让，不是抢） |
| `AgentLoop.java` | `send_message` 加 type / replyTo 参数；注册 `task_handoff`；策略段加协议用法 |

s15 的成员表、收件箱、轮转 tick、认领冲突检查全部保留。

## 信封

```json
{"id":"R1","from":"lead","to":"researcher","at":"14:59:42",
 "type":"request","payload":"日志最后一条 ERROR 的时间戳是什么格式？一句话答复。",
 "replyTo":"","deadline":"14:59:43"}
```

`type` 四个值，各管一件事：

| type | 谁发 | 义务 |
|---|---|---|
| `request` | 任何成员或 lead | 收件方必须答复；超时进发件人的死信 |
| `response` | 收到 request 的人 | `replyTo` 必须指回一条在等的 request，对不上拒收 |
| `broadcast` | 任何成员或 lead | 人人收到，含 lead——lead 只看得到广播和自己的信 |
| `handoff` | 任务 owner | 收件方下一轮必须 ACCEPT 或 REJECT，accept 才改 owner |

和 s15 的消息同一个文件（`.team/<name>/inbox.jsonl`），`type` 字段区分。
不写 type 就退回 s15 的普通消息——成员的结论投回 lead 走的还是老路。

## response 为什么必须指回一条 id

`Protocol.sendResponse()` 里的检查只有一步：

```java
if (!NAIVE && !pending.containsKey(replyTo)) {
    orphanReplies++;
    return "(error: orphan_reply: replyTo " + replyTo
            + " 没有对应的待答复请求，这条 response 被拒收。…)";
}
```

对不上就拒收。不然「答复」两个字没有任何含义——谁都能装作在回答，
而等答复的那个人永远不知道自己的问题没人接。

id 从哪来？信封渲染进上下文的时候带着：

```
<envelope id="R1" from="lead" type="request" replyTo="-">
日志最后一条 ERROR 的时间戳是什么格式？一句话答复。（14:59:43 前答复）</envelope>
```

收件方看得见 `id="R1"`，答复时才有处可抄。实测里 researcher 第 2 步的上下文
就有这个渲染，它的 `send_message(replyTo="R1")` 和盘上原 request 的 id 逐字相同。

## 成员之间直接说话

s15 里通道是通的——`Mailbox.post` 不看方向——但成员手里没有发信工具。
这一篇把 `send_message` 发到每个人手里，`to` 可以是另一个成员。

实测：researcher 直接点名 indexer 要一个事实，往返两封都在各自收件箱里，
lead 的收件箱里没有这对信封的原文：

```
{"id":"R3","from":"researcher","to":"indexer","type":"request",
 "payload":"帮我看 orders 表有没有联合索引，一句话回我。","replyTo":"","deadline":"14:59:42"}
```

轮转 tick 是串行的，带来一个 s15 没有的副作用：同一 tick 里，
researcher 的 step 发出 R3，indexer 的 step 就读到了——成员之间的往返可以快过一个 tick。
lead 的往返永远隔一个 tick，因为 lead 的消息在 tick 之后才发。

## 超时：死信是台账

`request` 发出时登记进 pending，deadline 写在信封上（`PROTOCOL_TIMEOUT_MS`，默认 800）。
每轮 tick 的第一行是 `sweep()`：过了 deadline 还没人答，从 pending 摘下来，
写进**发件人**的 `.team/<from>/dead.jsonl`：

```json
{"id":"R2","from":"lead","to":"slowhand","at":"14:59:43","type":"request",
 "payload":"orders 表上现在有几个索引？报个数就行。","replyTo":"",
 "deadline":"14:59:42","deadAt":"14:59:43","reason":"timeout 800ms 内未答复"}
```

实测这一条怎么压出来的：slowhand 第三步睡 950ms（> 800），
R2 的年龄在下一个 sweep 时越过了 deadline。本地假端点一轮几十 ms，
不睡这一下，「过期」永远量不出来——超时要以 tick 为刻度才测得了。

死信不只是文件。`team_status` 的最后一行是协议账：

```
Team 3/3：
  researcher  忙（第 3 步） 未读 1 条　角色：查清 data/app.log 的时间戳格式
  ...
协议：待答复 0 条，死信 1 条，孤儿应答 1 条
```

lead 查死信靠这一行，不用翻盘。

## handoff：owner 是让出来的，不是抢来的

`task_handoff(T2, indexer)` 只做两件事：登记移交、发一封 handoff 信封。
owner 一个字没改。indexer 下一轮 `task_update(T2, status=ACCEPT)` 才真正接走：

```
Handoff H1：T2 → indexer（owner 未变，等对方下一轮 ACCEPT / REJECT）
T2 owner 已移交 → indexer（accept）。现在是你的活，按正常流程认领推进。
```

accept 走的是 `TaskStore.transfer()`，绕过认领冲突检查——移交是原 owner 主动让，
不是第二个人来抢，s15 那个「已经被 X 认领」的检查在这里只会把合法移交挡死。

三处拒收，模型都读得到错误串：

```
(error: handoff 被拒：没有任务 T9)                        ← 任务不存在
(error: handoff 被拒：T2 的 owner 是 researcher，不是你)   ← 不是你的活，不能让
(error: orphan_reply: replyTo R999 没有对应的待答复请求…)  ← 答复对不上号
```

REJECT 也一样简单：owner 不变，移交登记销掉。不表态的移交一直挂着，
team_status 里「待表态移交」会一直数着它。

## 实测：20 次 POST

剧本 6 轮 lead、5 次 tick、20 次 POST，17 项断言全 YES：

```
SCENARIO=main POSTS=20
envelope_fields_on_disk=YES        request 带齐 id/from/to/at/type/payload/replyTo/deadline 八个字段
request_id_visible_in_context=YES  researcher 第 2 步的上下文里能看到 envelope id=R1
request_answered_next_tick=YES     第 5 次 POST 发出，researcher 第 6 次 POST 读到
reply_to_matches_request_id=YES    lead 收到的 response replyTo=R1，和原 id 逐字相同
pairing_closes_request=YES         第 5 轮 team_status：待答复 0 条，死信 1 条
timeout_dead_letter=YES            .team/lead/dead.jsonl 恰好 1 行，就是没人答的 R2
dead_letter_in_status=YES          team_status 协议行写着 死信 1 条
handoff_owner_unchanged_before_accept=YES  ACCEPT 那一刻 T2 的 owner 还是 ''
handoff_accept_changes_owner=YES   accept 执行完，同 tick 更晚的 POST 时 owner=indexer
handoff_final_owner=YES            T2 最终 owner=indexer、状态 PENDING
handoff_nonexistent_rejected=YES   task_handoff(T9) 被拒：没有任务 T9
direct_member_exchange=YES         R3 落在 indexer 收件箱，E2 的 replyTo=R3 指回它
lead_cannot_see_direct=YES         lead 收件箱里没有那对直聊信封的原文
broadcast_reaches_all=YES          broadcast 落进 lead/indexer/slowhand 三份收件箱
orphan_reply_rejected=YES          R999 被拒收，错误串回了 slowhand 的上下文
tick_round_robin_order=YES         20 次 POST 的顺序和轮转表逐项相同
script_finished=YES                剧情 6 轮跑完
```

收尾那一行：

```
   [team] 成员 3/3，累计起过 3 个，tick 5 次，投回 lead 5 条；
   信封 7 封，request 3，response 2，broadcast 1，handoff 1；配对成功 2，死信 1，孤儿应答 1
```

7 封信封，逐封对得上：R1（lead→researcher）、R2（lead→slowhand，进死信）、
H1（T2 移交）、E1（researcher 答 R1）、R3（researcher→indexer）、
E2（indexer 答 R3）、B1（researcher 广播）。发出去 3 封 request，
回来的 response 2 封 + 死信 1 封 = 3，账是平的。

## 反例：不查 replyTo、不清超时

`PROTOCOL_MODE=naive` 把配对检查和 sweep 都关掉。同一份剧本：

```
SCENARIO=naive POSTS=20
naive_dead_letter_zero=YES         .team/lead/dead.jsonl 0 行——main 那一遍是 1 行
naive_orphan_reply_delivered=YES   孤儿应答被当成正常回执投进 lead 收件箱
naive_no_rejection_seen=YES        slowhand 的上下文里没有拒收错误
naive_unanswered_still_pending=YES 第 5 轮 team_status：待答复 3 条，死信 0 条
```

对比数字：main 死信 1 / naive 死信 0；main 待答复 0 / naive 待答复 3
（两封被答的 + 一封被无视的都还挂在账上，被答的也不销账）；
main 孤儿应答 1 条被拒 / naive 那封 replyTo=R999 的废信原样落进 lead 收件箱，
而且 slowhand 的上下文里没有任何错误——它根本不知道自己发的是废信。

账面上一切正常，账是错的。这种事在只有一次投递的时候不会发生，
有了「必须答复」这个义务，它就一定会发生。

## 这一篇没解决的

- **没有重发**。request 进死信之后没有第二条路：不会自动重发，也不会通知收件方「你欠一封答复」。死信是台账，不是补救。
- **handoff 没有超时**。request 有 deadline，移交没有。对方一直不表态，移交就一直挂着。
- **无身份校验**。`from` 是工具调用者自己填的名字，成员可以冒充别人发 request。协议管「对不对得上」，不管「你是不是你」。
- **deadline 用 wall clock**。真实模型一次回答几秒，800ms 的默认值在真 API 上会把一切答复判死。本地实验里靠假端点控速，真要用得先把超时改成按 tick 计。
- **tick 串行**。s15 的老账：一个成员慢（这一篇甚至故意睡 950ms），全队等它。
- **成员不能写文件**。并行只在「查」这一侧成立，写的还是回 lead。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 成员上限 | `TEAM_MAX` 默认 3 |
| 收件箱目录 | `TEAM_DIR` 默认 `.team`（死信在同名目录的 `dead.jsonl`） |
| 任务目录 | `TASK_DIR` 默认 `.tasks` |
| 协议模式 | `PROTOCOL_MODE`：checked（默认）/ naive |
| 答复期限 | `PROTOCOL_TIMEOUT_MS` 默认 800 |
| 剧本（main） | 6 轮 lead / 5 次 tick / 20 次 POST |
| 成员步数 | researcher 5、indexer 4、slowhand 5 |
| 假端点控速 | slowhand 第 3 步睡 950ms，把 R2 的年龄压过 deadline |

`.tasks/`、`.team/`、`data/`、`db/` 都在 `.gitignore` 里，`data/` 和 `db/` 由假端点在
启动时造出来，测试产物不入库。

## 跑一遍

```sh
SCENARIO=main python3 fake-endpoint.py &        # 端口 8916，会清空 .tasks/ .team/ 重建
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8916/v1 \
  MODEL_ID=fake mvn -q exec:java
cat /tmp/s16_assert.txt                          # 17 项全 YES

SCENARIO=naive python3 fake-endpoint.py &        # 反例，要配 PROTOCOL_MODE=naive
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8916/v1 \
  MODEL_ID=fake PROTOCOL_MODE=naive mvn -q exec:java
cat /tmp/s16_assert_naive.txt                    # 4 项全 YES
```

两遍都必须是「先起端点、再起 JVM」：端点要在第一次请求之前把 `.tasks/` 清干净。
