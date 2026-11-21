# s12 · Task System

s05 的待办清单只有一个字段是可靠的：它在这一轮里是对的。
会话一结束就没了，重启就没了，压缩之后只剩摘要里的一句话。

## 问题

s05 那版 `TodoStore` 有三处「够用就好」：

| 写法 | 当时为什么可以 | 什么时候不行 |
|---|---|---|
| 存在内存里 | 一次会话内够用 | 进程被杀、换台机器、第二天接着干 |
| 一张平表 | 三五件事不需要依赖 | 十件事、有先后、有人认领 |
| 「做完」由模型说 | 模型通常诚实 | 它说做完的时候，前置可能根本没做 |

第三处最隐蔽。模型把 `T3` 标成 `completed`，没有任何东西会问它「T3 的前置完成了吗」。
摘要更不会——摘要只会把「T3 已完成」这句话抄进去，然后这句话就变成了事实。

## 相对 s11 的改动

| 文件 | 改了什么 |
|---|---|
| `TaskStore.java` | 新增。落盘、依赖、认领、成环检测、孤儿降级 |
| `AgentLoop.java` | 删掉 `TodoStore`；注册 `task_create` / `task_update` / `task_list`；system 的 `tasks` 段换成任务板 |
| `fake-endpoint.py` | 重写。两个场景：主流程 11 轮、重启读盘 |

`s11` 的错误恢复、循环检测、前缀排序全部保留。

## 一个任务就是一个文件

```
.tasks/
  T1.json
  T2.json
  T3.json
  seq
```

`T2.json`：

```json
{
  "id" : "T2",
  "subject" : "写回滚方案",
  "status" : "ORPHANED",
  "blockedBy" : [ "T1" ],
  "owner" : "lead",
  "createdAt" : "11-21 13:56:12",
  "updatedAt" : "11-21 13:56:23",
  "note" : "上次会话到这里就断了"
}
```

一个任务一个文件，不是为了好看。是为了改一个任务不用重写整张表——
并发认领的时候，两个人改的如果是不同文件，就不会互相覆盖。

`seq` 单独存一个文件，重启后新任务从 `T4` 继续。这个细节不写会很难受：
每次重启都从 `T1` 开始，历史记录全被覆盖。

## 依赖是约束，不是建议

```java
if (status == Status.IN_PROGRESS) {
    List<String> waiting = new ArrayList<>();
    for (String dep : t.blockedBy()) {
        Task d = tasks.get(dep);
        if (d == null || !d.done()) {
            waiting.add(dep);
        }
    }
    if (!waiting.isEmpty()) {
        throw new IllegalStateException(id + " 还等着 " + String.join(",", waiting) + " 完成");
    }
}
```

这段放在 `task_update` 里，不是写在提示词里让模型自己注意。

提示词是建议，代码是约束。写在提示词里，模型九次遵守、一次不遵守，那一次就是
「T3 在 T1 没做完的时候被标成了进行中」，而且没人知道。写在代码里，它连试都试不成：

```
→ task_update(T3 → IN_PROGRESS)
<tool-error tool="task_update">(error: T3 还等着 T2 完成)
这次调用失败了。按错误信息改参数或换工具，别把同样的调用再发一次。</tool-error>
```

错误信息里带上了等谁。模型下一轮就知道该先做 T2，而不是再试一次 T3。

## 依赖会变，所以得查环

需求一变，「谁等谁」就得重排。所以 `task_update` 也接受 `blockedBy`：

```java
Task candidate = new Task(t.id(), t.subject(), t.status(), List.copyOf(blockedBy), ...);
tasks.put(id, candidate);
if (createsCycle(id)) {
    tasks.put(id, t);                    // 回滚
    throw new IllegalArgumentException("这个依赖会形成环：" + id + " ← " + ...);
}
```

不查环的后果不是报错，是安静地卡死：A 等 B、B 等 A，两个都永远 `PENDING`，
`ready()` 永远是空，而系统一切正常。

实测里把 `T1` 的依赖改成 `T3`（T3 等 T2，T2 等 T1），直接被拒：

```
<tool-error tool="task_update">(error: 这个依赖会形成环：T1 ← T3)
```

## 重启：in_progress 一律降级

```java
if (t.status() == Status.IN_PROGRESS) {
    t = t.with(Status.ORPHANED, null, "上次会话到这里就断了");
    store.save(t);
}
```

上次会话死在一个 `in_progress` 的任务上，这件事必须留痕。
不降级的写法会让新会话看到「T2 正在进行中」，然后它就不管了——实际上没有任何进程在做。

`ORPHANED` 也不是「失败」，它是一个问句：继续，还是重开？这件事交给模型判断，
但得让它看见。

## 进 system 的那一段：看得见 ≠ 该做

```
Tasks on disk (they survive restarts; only the "ready" ones are yours to start):
  ready to start:
    T1 写迁移脚本
  blocked (do NOT start these yet):
    T2 写回滚方案 ← blocked by T1
    T3 补联合索引 ← blocked by T2
  orphaned from last session: T2 写回滚方案（决定继续还是重开）
  completed: 1
```

五栏，作用不一样：

- `ready` 是「现在可以动手的」。
- `blocked` 是「存在，但现在别碰」。**把它们藏起来是错的**——模型需要知道后面还有活，
  否则它会把 T1 做完就宣布任务结束。
- `in progress` 是「已经有人在做的」。
- `orphaned` 是「上次断在这儿的」。
- `completed` 只给个数，不列明细：做完的事除了占 token 没别的用。

这一段挂在 system 的 `ROUND` 档（s10 定义的最后一档）。它每改一次任务就变，
但它垫在最后，前面攒的前缀不受影响——实测里改任务时打印的是「前缀未变（缓存还能用）」。

如果任务全都完成了，`ready` 和 `blocked` 两栏都会消失，只剩 `completed: 3`。
空栏不输出，不是输出一行 `(empty)`。

## 实测

主流程 11 轮、11 次 POST，11 项断言全 YES：

```
tasks_on_disk=YES             .tasks/ 下 3 个文件，不是内存里的一张表
dependency_saved=YES          T2 ← T1，T3 ← T2，依赖写在文件里
status_saved=YES              T1 COMPLETED / T2 IN_PROGRESS，重启不会丢
blocked_task_rejected=YES     T3 前置没完成，task_update 直接被拒
cycle_rejected=YES            把 T1 的依赖改成 T3 会绕回去，被拒
blocked_not_in_ready=YES      被挡住的进 system 时单独一栏
ready_section_present=YES     中间有任务可开工时单独列出；最后没有可开工的，这一栏消失
board_in_system_tail=YES      任务板垫在 system 末尾
no_lost_update=YES            剧本 11 轮跑完
script_finished=YES
```

然后**把进程杀掉，重新起一遍**，5 项断言全 YES：

```
tasks_restored=YES            重启后读回 4 个任务
orphan_marked=YES             上次死在 in_progress 的 T2 被标成 ORPHANED
seq_continues=YES             新建的是 T4，不是从 T1 再来一遍
orphan_visible_in_system=YES  孤儿任务进了 system 的那一段
script_finished=YES
```

启动那一行就能看出状态是不是真的从磁盘来的：

```
   [task] 磁盘上 3 个任务：完成 1，可开工 0，孤儿 1
```

## 跑一遍

```sh
SCENARIO=main    python3 fake-endpoint.py &   # 端口 8912，会清空 .tasks/ 重建
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8912/v1 \
  MODEL_ID=fake mvn -q exec:java

SCENARIO=restart python3 fake-endpoint.py &   # 不清盘，直接读上一轮留下的 .tasks/
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8912/v1 \
  MODEL_ID=fake mvn -q exec:java
```

重启那一遍必须紧接着跑，中间不要清 `.tasks/`——它测的就是「盘上还有东西」。

## 这一篇没解决的

- **没有并发控制**。两个进程同时改同一个任务，后写的赢。真实场景要给文件加锁或版本号。
- **没有 owner 校验**。谁都能改任何任务，`owner` 只是个标签。
- **没有时间维度**。任务没有 due、没有依赖超时，醒来发现「T1 三天没动」这种事不会发生。
- **没有历史**。改一次覆盖一次，看不到状态变迁。想审计得自己加 append-only 的日志。
- **任务和 messages 是两套状态**。压缩只压 messages，任务板在 system 里活着——这是好事，
  但也意味着「任务里写的东西」和「对话里说的东西」可以长期不一致，没有对账机制。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 任务目录 | `TASK_DIR` 默认 `.tasks` |
| 剧本（main） | 11 轮：建 3 个任务、列 1 次、改状态 5 次（含 1 次违规、1 次成环） |
| 剧本（restart） | 2 轮：新建 1 个任务，验证序号续上 |
| 上下文预算 | `CONTEXT_LIMIT` 默认 8000（本篇不触发压缩） |
| 系统提示预算 | `PROMPT_BUDGET` 默认 1200；实测首轮 1171 |

`.tasks/` 和 `data/` 都在 `.gitignore` 里，测试产物不入库。
