# s09 · Memory 跨会话记忆

## 解决什么问题

前面八篇做的东西全都在 `messages` 里：工具结果、子 Agent 结论、技能正文、压缩摘要。它们都有同一个毛病——**这一轮结束就没了**。

昨天你告诉它「这个项目的错误返回是 `{error:{code,message}}`，没有 `type`」，它照做，做对了。今天新开一个会话，它又按 OpenAI 的 `error.type` 写，因为昨天那条消息早被压掉了。

把这句话塞进 system prompt 也没用：那是写死在代码里的，改一次要发版；而「下次别再用 `mvn install`」这种话是**运行时才学到的**，不是设计时就知道的。

`s09` 做的是第一件活在 `messages` 外面的事：**记忆写到磁盘上两个文件，每一轮重新拼进 system prompt**。上下文压缩删的是 `messages`，碰不到它；进程重启也还在。

## 相对 s08 的变更

| 组件 | s08 | s09 |
| --- | --- | --- |
| 工具数 | 8 | 10（`memory_write` / `memory_read`） |
| 新代码 | — | `MemoryStore`：两级文件、目录、正文、覆盖、淘汰 |
| 循环 | `while(true)` | 同一个循环，多一处「目录变了就重拼 system」 |
| `UserPromptSubmit` | 返回值没人用 | 第一次被用上：用户说「记住」时注入提示 |
| hook | 4 个 | 5 个（多一个 `memoryHintHook`） |

循环里多的那一段：

```java
// s09：这一轮写过记忆，就把 system 里的目录重拼一次。
// 不是每轮都拼——system 一变，前缀缓存就白攒了，只在真的变了的时候付这个代价
if (memory.version() != memoryVersion) {
    memoryVersion = memory.version();
    system.put("content", SYSTEM + memory.indexSection() + skills.catalogSection());
}
```

## 四件事

### 1. 存什么：一句可复用的事实

`MAX_BODY = 400`。超了不是截断，是**拒绝**：

```
   [memory] ⛔ 拒绝写入：正文 765 字符，超过上限 400。
             记忆存的是一句可复用的事实，不是过程记录：把结论压成一句话再写
```

截断比拒绝更糟——半句话下次看不懂，还会被当成完整事实用。

反过来也别什么都存：文件里能查到的不存（代码结构、函数签名）、本轮任务的不存（待办清单有 `todo_write`）、能推出来的不存。

### 2. 存哪儿：项目级 / 用户级两个文件

| scope | 文件 | 放什么 | 进仓库吗 |
| --- | --- | --- | --- |
| `project` | `memory/project/MEMORY.md` | 这个仓库的约定：索引命名、构建命令、接口形状 | 进，团队共享 |
| `user` | `memory/user/MEMORY.md` | 跟着人走的偏好：回答风格、编辑器、时间预算 | 不进 |

同名 key 两级都有时，**目录只显示项目级那条**——项目约定比个人印象更具体，也更可能是对的。

### 3. 什么时候写

三处触发，都不靠模型自觉：

1. 用户说「记住」→ `UserPromptSubmit` 的 `memoryHintHook` 注入一句 `<memory-hint>`，把「现在是写入时机」这个判断明确告诉模型；
2. 系统提示里写清「确认了一个结论就写」——项目约定、固定返回形状、刚踩过的坑；
3. 写入即落盘（`Files.writeString`），不等退出：进程被 Ctrl-C 也不会丢。

### 4. 怎么读：目录常驻，正文按需

- 目录：每条一行 `- key [scope] — 36 字摘要`，拼在 system prompt 里；
- 正文：`memory_read(key)` 才把全文读进上下文。

实测规模：35 条记忆，目录 34 行 **726 token**，全文合计 **2068 token**。每轮付的是 726。

省 65% 不是重点。重点是**正文长度和常驻成本解耦**：一条记忆可以带例子、带回滚 SQL、带「为什么这么定」，而不用为了「每轮都要付这笔钱」把话说到半截。

写完记忆这一轮，system 里的目录会重拼一次——所以本轮新写的东西，下一轮就出现在目录里。

## 防脏四条

1. **同名 key 覆盖，不追加**。改主意是常态，追加只会留下两条互相矛盾的记忆，模型挑哪条都对。
2. **上限 30 条，超了淘汰最久没读的**。`reads` / `last-read` 每次读取都落盘，淘汰才有依据可算。
3. **工具不收路径**。`memory_write` 的参数只有 `scope` / `key` / `text`，路径由 `MemoryStore` 自己拼——`write_file` 有越界检查，自定义工具没有，一旦接受路径就等于给模型开了后门。
4. **写完重拼 system，不是每轮重拼**。改 system 会让前缀缓存失效一次，只在目录真的变了的时候付这个代价。

## 记忆文件长什么样

```markdown
# MEMORY · project

## api-error-shape
updated: 2026-11-21T13:01:34
last-read: 2026-11-21T13:01:34
reads: 2
接口错误返回固定是 {"error":{"code":"...","message":"..."}}，没有 type 字段。
解析走 code；HTTP 状态码不一定是 200，先判 error 再判 data。
```

`##` 切条，三行元数据（`updated` / `last-read` / `reads`），其余是正文。解析就是按行扫，不引 YAML 库——和 s07 读 `SKILL.md` 是同一套写法。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 接口 | OpenAI 兼容：`OPENAI_BASE_URL` / `OPENAI_API_KEY` |
| temperature | 未显式设置（用服务端默认） |
| 初始记忆 | `memory.seed/`：项目级 30 条、用户级 5 条（其中一条与项目级同名） |
| 单条正文上限 | 400 字符 |
| 单 scope 条数上限 | 30 |
| 目录摘要长度 | 36 字符 |
| 上下文预算 | `CONTEXT_LIMIT`，默认 8000；实测设 5000 |
| max_tokens | 8000 |

## 怎么跑

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1
mvn -q clean compile
mvn -q exec:java
```

默认任务里带一句「记住：……」，用来触发写入提示。想验证机制而不花钱，用仓库里的假端点：

```sh
python3 fake-endpoint.py            # 会把 memory.seed/ 复制成 memory/，再生成 data/big-1..3.log
CONTEXT_LIMIT=5000 OPENAI_API_KEY=sk-fake \
  OPENAI_BASE_URL=http://127.0.0.1:8909/v1 MODEL_ID=fake-model mvn -q exec:java
```

## 验证输出（假端点，实际执行）

```
   [memory] 项目级 30 条（memory/project/MEMORY.md），用户级 5 条（memory/user/MEMORY.md）
   [HOOK] memoryHintHook: 用户要求记住，注入写入提示
→ todo_write([3 tasks])
→ memory_read("api-error-shape")
   [memory] 读 api-error-shape（project，第 2 次被读，198 字符）
→ memory_write(project:api-error-shape)
   [memory] 写 api-error-shape（project，覆盖旧值，117 字符）
   [memory] 目录变了，重拼 system prompt
→ memory_write(user:reply-style)
   [memory] 写 reply-style（user，覆盖旧值，36 字符）
→ memory_write(project:session-log-2026-11-21)
   [memory] ⛔ 拒绝写入：正文 765 字符，超过上限 400。
→ memory_write(project:log-format)
   [memory] 写 log-format（project，新增，49 字符），淘汰 rollback
→ memory_read("reply-style")
   [memory] 读 reply-style（user，第 2 次被读，36 字符）
→ read_file({"path":"data/big-1.log"})
→ read_file({"path":"data/big-2.log"})
   [compact] 第 1 次压缩：17 条 → 1 条摘要，上下文 3850 → 2982 token（估算）
→ read_file({"path":"data/big-3.log"})
→ todo_write([3 tasks])
   [compact] 第 2 次压缩：5 条 → 1 条摘要，上下文 3863 → 2231 token（估算）
   [HOOK] Stop: 本会话共 11 次工具调用

   [compact] 本会话压缩 2 次，累计省下 2500 token（估算）

   [memory] 磁盘上 35 条：目录 34 行 ≈ 726 token，全文合计 ≈ 2068 token
   [memory] 本会话：读 2 次（命中 2，未命中 0），写 4 次（采纳 3，拒绝 1）
```

二十五项断言：

```
index_in_system=YES            磁盘上的记忆目录进了 system prompt
index_digest_only=YES          目录里只有摘要，正文没进上下文
project_overrides_user=YES     同名 key 两级都有，目录只显示项目级那条
hint_injected=YES              用户说了「记住」，注入了写入提示
index_line_count=YES           34 行 = 30 + 5 - 1 条同名去重
cross_session_read=YES         读到的是上一次会话写进文件的正文
overwrite_messaging=YES        同 key 写入被识别为覆盖
index_refreshed=YES            写完记忆，下一轮 system 里的目录立刻变了
user_scope_write=YES           用户级写入走了另一个文件
process_rejected=YES           765 字的过程日志被拒
lru_evict_messaging=YES        第 31 条进来，淘汰最久没读的那条
user_level_read=YES            用户级记忆也能按需读全文
memory_survives_compact=YES    压缩删了 messages 中间那段，system 没动
read_body_dropped=YES          读回来的正文被压掉了
head_kept=YES                  头两条还是 system + 原始目标
overwrite_not_append=YES       文件里只有一段 api-error-shape
reads_persisted=YES            读取计数落盘，淘汰有据可依
scope_separation=YES           用户级写在用户文件里，项目文件里没有
reject_not_persisted=YES       被拒的写入没有落盘
lru_evicted=YES                30 条上限成立，rollback 被淘汰
new_entry_saved=YES            新 key 正常写入
index_in_every_request=YES     12 个会话请求，每次都带着记忆目录
pairing_intact=YES             全程 0 次孤立 tool 消息
compaction_happened=YES        至少压过一次
script_finished=YES            剧本 12 轮跑完
```

`memory_survives_compact` 和 `read_body_dropped` 是一对：压缩删掉中间那段之后，**读回来的记忆正文不见了，system 里的目录还在**。这就是「记忆不能只放在上下文里」的实测依据。

## 简化说明

- 没有语义检索。目录按 key 字典序，模型自己看哪条相关；条数涨到几百条就得换检索。
- 没有冲突检测。同名 key 覆盖是最省事的策略，但两条 key 不同、内容矛盾（`api-error-shape` 和 `api-error-shape-v2`）时，系统不会提醒。
- 没有「这条记忆过期了」的机制。`updated` 只写不判，靠人和模型自己看日期。
- 用户级记忆不区分项目来源：换了项目还带着上一个项目的偏好，可能不合时宜。
- 写入没有二次确认。真实产品里，写用户级记忆之前应该让人看一眼。
