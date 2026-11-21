# s13 · Background Tasks

s02 的 bash 是同步的：`readLine` 阻塞到命令结束。一条 `npm test` 跑三分钟，
这三分钟里循环停在 `registry.dispatch` 那一行，模型什么都做不了。

这一篇只做一件事：**让命令在循环外面跑，让循环接着干活。**

## 问题

同步 bash 的代价不是「慢」，是「整轮对话被一条命令绑住」：

- 测试跑三分钟，模型这三分钟不能读文件、不能改代码、不能回答用户
- 想同时跑两个慢命令，只能串行，第二个要等第一个
- 命令超过 60 秒被 `waitFor` 掐掉，前面的输出也一起丢了
- 输出全在内存里，进程一死什么都没有

这四个问题里，前三个都是「等」，第四个是「没落盘」。

后台任务拆成两半：**进程归操作系统管，输出归文件管**。循环只负责在每轮开始前问一句
「有没有谁跑完了」。

## 相对 s12 的改动

| 文件 | 改了什么 |
|---|---|
| `BackgroundTasks.java` | 新增。start / output / kill / poll + 元信息落盘，约 300 行 |
| `AgentLoop.java` | 注册 `bash_bg`、`bash_output`、`kill_task`；每轮请求前 `bg.poll()`，结束通知作为一条 user 消息注入 |
| `fake-endpoint.py` | 重写。19 轮剧本：后台任务、并行干活、两次增量读、非零退出、超时、kill、200 行截断 |

`bash` 留在原地不动。后台不是替代它，是给它加一个「不用等」的选项。

## 进程和输出都落盘

```java
ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
pb.redirectErrorStream(true);          // stderr 并进来，错误和正常输出才排得上先后
pb.redirectOutput(out.toFile());       // .bg/bg_1.out
p = pb.start();                        // 立刻返回，不等
```

`s02` 是 `p.getInputStream()` + `readLine` 循环。那个循环就是阻塞点：没有新行的时候它停在那里等。
`redirectOutput` 把这件事交给内核，Java 侧一行都不读。

元信息写 `.bg/<id>.json`：

```json
{ "id": "bg_1", "pid": 81234, "status": "exited",
  "command": "for i in 1 2 3 4 5 6; do echo line-$i; sleep 0.5; done",
  "exit_code": 0, "offset": 21, "output": ".bg/bg_1.out" }
```

`exitCode` 单独记一份不是给模型看的，是给下一个进程看的。进程没了，文件还在——
`ps` 查不到的东西，至少还有一份记录。这一篇没做恢复（重启后读回 `.bg/`），
但字段先留好了。

## 增量读：一行 offset

`bash_output` 是这一篇最容易写错的地方。最直觉的实现是「把日志文件读出来返回」：

```java
return Files.readString(out);          // 错
```

模型拿到全文，看到最后一行不是它要的，于是再调一次——又是全文。同一段读五遍，
上下文里五份一样的日志，token 花掉五倍，而模型自己不知道重复了。

正确的那一行：

```java
String chunk = new String(all, (int) job.offset, (int) (all.length - job.offset), UTF_8);
...
job.offset += fresh.getBytes(UTF_8).length;   // 读完就推进
```

`offset` 推进到**最后一个完整行的末尾**，不是文件末尾（`cut = chunk.lastIndexOf('\n')`；
一行都没写完就先不回，进程已结束则残行照给）。

理由是两件事。进程可能正好写到一半，切在文件末尾会把半个汉字留在这一轮、
把另一半留给下一轮，解出来是乱码。切在行中间则下一轮会重复半行——
「不重复」是这个字段唯一的价值，半行重复也算重复。

实测（`bg_1` 每秒吐一行，共六行）：

```
→ bash_output(bg_1)
[bg_1 running]
line-1
line-2

→ bash_output(bg_1)          # 2.6 秒之后
[bg_1 exited 0]
line-3
line-4
line-5
line-6
```

第一次 28 字符，第二次 43 字符。第二次里没有 `line-1`。

## 攒太多就截断

不设上限，一条 `yes` 就能把上下文撑爆。所以单次最多回 `READ_TAIL_LINES = 40` 行：

```
（本次新增 200 行，只回最后 40 行；更早的 160 行已丢弃）
```

丢弃的行不补。要精确回溯得自己加 `offset` 参数重读——这一篇没做，
因为真实场景里「四个小时前那 160 行日志」通常不如「重新跑一遍」便宜。

截断的判定放在读的时候，不是写的时候：写的人（子进程）不该知道读的人攒了多久。

## 完成通知走 messages，不走 system

`poll()` 每轮调一次，返回这一轮**新结束**的任务：

```java
for (Job job : jobs.values()) {
    if (job.reported) continue;            // 只报一次
    if (job.process.isAlive()) { ... 超时判定 ... continue; }
    job.exitCode = job.process.exitValue();
    job.status = "exited";
    job.reported = true;
    done.add(new Finished(job.id, job.exitCode, "exited", tail(job, NOTICE_TAIL_LINES)));
}
```

循环拿到之后，拼成一条 user 消息塞进 messages：

```
[background] bg_1 finished (exit 0)
最后 6 行：
line-1
line-2
...
```

不写进 system。理由是 s10 那一篇的：system 是缓存前缀，任何一处变了，后面的 token 全部重算。
任务完成通知是最典型的**临时事件**——它只对接下来几轮有用，之后就只是历史。
把它写进 system，等于每完成一个任务就把 4659 个字符的前缀作废一次。

实测 19 个请求，system 一个字节没变：

```
system_unchanged=YES (19 个请求，1 种 system，md5 7ec1231b，4659 字符)
notice_in_messages=YES (第 5 轮请求的 messages 里出现 [background] bg_1 finished)
notice_not_in_system=YES (system 里搜不到 [background])
```

通知只带最后 20 行（`NOTICE_TAIL_LINES`）。整段输出塞进通知，等于把增量读白做了。

## 超时和 kill 都落在 poll 里

超时的判定不能放在启动的时候等——那就又变回同步了。只能放在 poll：

```java
long elapsed = (System.currentTimeMillis() - job.startedAt) / 1000;
if (elapsed >= job.timeoutSec) {
    destroyTree(job.process);
    job.exitCode = waitExit(job.process);
    job.status = "timeout";
}
```

`destroyForcibly` 杀的是 `sh`，不是 `sleep`：

```java
p.descendants().forEach(ProcessHandle::destroyForcibly);
p.destroyForcibly();
```

`sh -c "sleep 30"` 里 sh 是父进程、sleep 是子进程。只杀父进程，sleep 还活着，
输出文件也不关闭，看起来像「杀不掉」。实测两个都死在 137（128 + SIGKILL）：

```
   [bg] bg_3 超过 1s 没结束，强制杀掉（exit 137）
   [bg] bg_4 被 kill_task 结束（exit 137）
```

超时和 kill 的区别只有一个：谁先动手。状态记成 `timeout` 还是 `killed`，
是为了事后能分清「它太慢」和「我不要了」。

## 实测

19 轮剧本，一个 3 秒的后台任务贯穿前半段：

```
   [bg] bg_1 启动 pid 81234，超时 30s：for i in 1 2 3 4 5 6; do echo line-$i; sleep 0.5; done
bg_1 started (pid 81234, timeout 30s). Read it with bash_output(bg_1); this call returned immediately.
   [bg] 1 个任务结束，通知作为 user 消息注入（messages 14 条，system 4659 字符未变）
```

起任务那一轮到下一轮请求只隔了 76ms，而任务本身跑了 3.7 秒
（3 秒的 sleep + 进程启动 + 检测延迟）：

```
loop_not_blocked=YES (起任务→下一轮 76ms，任务本身 3.7s)
```

十五项断言：

```
task_meta_on_disk=YES        .bg/bg_1.json pid=81234，命令原样存着
loop_not_blocked=YES         起任务→下一轮 76ms，任务本身 3.7s
first_read_partial=YES       第一次 28 字符，有 line-1，没有 line-6
second_read_incremental=YES  第二次 43 字符，没有 line-1，有 line-6
notice_in_messages=YES       第 5 轮请求的 messages 里出现 [background] bg_1 finished
notice_is_user_role=YES      通知不是 system，是一次请求里新加的 user 消息
system_unchanged=YES         19 个请求，1 种 system，md5 7ec1231b
notice_not_in_system=YES     system 里搜不到 [background]
exit_code_recorded=YES       bg_2 exit_code=3
exit_code_in_notice=YES      通知里写着 bg_2 finished (exit 3)
timeout_auto_killed=YES      bg_3 status=timeout，进程已不在
kill_task_works=YES          bg_4 status=killed，pid 已退出
output_truncated=YES         200 行只回了 40 行，丢了 160 行
tool_pairing_intact=YES      全部请求 tool_call 都有回应
script_finished=YES          剧本 19 轮跑完
```

收尾的汇总：

```
   [prompt] 本会话 20 轮：前缀复用 19 次，重算 1 次；裁掉的段 workspace
   [bg] 启动 5 个，正常结束 3，超时 1，被杀 1；bash_output 6 次共回 239 字符，截断丢弃 160 行
```

`bash_output` 6 次一共回了 239 字符。如果每次都回全文，这 6 次里至少有一半是重复的。

## 反例：把通知写进 system

`BG_NOTICE=system` 把它当成 system 的开头——「通知是模型必须看到的信息，放最前面最不容易漏读」。
这个直觉在 s10 的排序规则里是灾难：

```
   [prompt] 重写 messages[0]：变了 background｜前缀 0ca4f7c0 变了
   [bg] 1 个任务结束，通知被拼进 system（反例：前缀作废）
```

同一份剧本，19 个请求：

```
naive_notice_in_system=YES    system 里出现了任务完成通知
naive_system_changed=YES      19 个请求拼出 5 种 system
naive_no_user_notice=YES      messages 里没有通知，全进了 system
naive_prefix_collapsed=YES    4 对变了的相邻请求，公共前缀最多 237 字符，是 4659 的 5%
```

237 字符是上一次通知的正文。也就是说，每次任务结束，缓存只能复用到上一段通知结束为止，
后面 4422 个字符全部重算。

两种写法的前缀复用次数（`SystemPrompt` 自己的账）：

```
messages：前缀复用 19 次，重算 1 次
system  ：前缀复用 15 次，重算 5 次
```

19 轮里 4 次通知，`system` 那一遍多算 4 次。任务越多，差得越多。

## 这一篇没解决的

- **重启即丢**。`.bg/*.json` 落盘了，但没有读回来的代码。进程死了，任务就是死了，模型只会看到上一次读到的位置。
- **poll 只在轮次边界**。模型一次请求要 20 秒，任务在第 1 秒就结束了，通知要等这 20 秒过完才注入。真要实时，得有回调把它塞进消息队列。
- **输出是文件，不是流**。子进程如果缓冲（Python 不加 `-u`），文件里半天没有一行，`bash_output` 就一直是「还没有完整的一行」。
- **没有并发上限**。`bash_bg` 起一百个就真起一百个，没有池子，没有排队。
- **截断不可逆**。丢掉的行不补，模型只能知道「丢了 160 行」，不能知道内容。
- **僵尸进程没管**。被 kill 的进程如果自己有子进程树更深，这里只杀一层 descendants。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | 假端点（`MODEL_ID=fake`），剧本固定 19 轮 |
| 后台任务 | `for i in 1..6; do echo line-$i; sleep 0.5; done`，3 秒 |
| 读上限 | `READ_TAIL_LINES = 40`；通知带 `NOTICE_TAIL_LINES = 20` |
| 默认超时 | `DEFAULT_TIMEOUT_SEC = 600`，剧本里显式给 1s / 30s / 60s |
| 目录 | `.bg/`（`BG_DIR` 可改） |
| 反例开关 | `BG_NOTICE=system` |
| 断言 | 正常 15 条 + 反例 5 条 |

时间相关的数（76ms、3.7s）每次跑都会抖，所以断言写的是「间隔 < 1 秒且任务 > 2.5 秒」，
不是等值。237 / 4659 这两个数取决于通知正文的长度，剧本固定，所以能复现。

## 跑一遍

```sh
cd build-claude-code-java/core/s13-bg
mvn -q clean compile

SCENARIO=main python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8913/v1 \
  MODEL_ID=fake mvn -q exec:java
cat /tmp/s13_assert.txt

# 反例
SCENARIO=naive BG_NOTICE=system ASSERT_LOG=/tmp/s13_assert_naive.txt \
  STDOUT_LOG=/tmp/s13_stdout_naive.log python3 fake-endpoint.py &
BG_NOTICE=system OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8913/v1 \
  MODEL_ID=fake mvn -q exec:java
cat /tmp/s13_assert_naive.txt
```
