# s10 · System Prompt 分段拼装

## 解决什么问题

前九篇往 system prompt 里塞了三样东西：身份和工具策略（s01–s05 一路长出来的）、技能目录（s07）、记忆目录（s09）。塞的办法一直是字符串相加：

```java
system.put("content", SYSTEM + memory.indexSection() + skills.catalogSection());
```

能跑。但三件事混成一坨之后，有三个问题答不上来：

1. **下次加新东西，放第几段？** —— 随便放，反正拼接顺序就是调用顺序。
2. **这段变了，前面攒的缓存还算不算？** —— 答不上来，因为没人知道「前面」是哪一段。
3. **system 也有预算，超了砍谁？** —— 没这个概念，一直是不限长。

`s10` 把这段字符串拆成**段**：每段带一个「变化频率」和一个段内序号，拼的时候按频率排序，拼完算指纹。

排序不是审美问题。模型服务的前缀缓存按 token 前缀命中，**第一处不同的字符之后全部作废**。把一段每轮都变的文本排到前面，等于让后面所有内容每轮都重付一次钱——这是这一篇唯一真正贵的地方。

## 相对 s09 的变更

| 组件 | s09 | s10 |
| --- | --- | --- |
| system 来源 | `SYSTEM` 常量 + 两段拼接 | 6 段，各自声明 `Kind` 和 `rank`，排序后拼接 |
| 重拼时机 | `memory.version()` 变了才重拼 | 每轮都拼，比字节，没变就不动 |
| 新代码 | — | `SystemPrompt`：排序、裁剪、前缀指纹、段级 diff |
| 循环 | 一个 `if` 判断版本号 | 一个 `if` 判断字符串是否相等 |

s09 那三行被删掉了：

```java
// s09
if (memory.version() != memoryVersion) {
    memoryVersion = memory.version();
    system.put("content", SYSTEM + memory.indexSection() + skills.catalogSection());
}
```

换成：

```java
// s10
SystemPrompt.Assembled now = buildPrompt();          // 每轮重拼
if (!now.text().equals(lastSystem)) {                // 只比字节
    system.put("content", now.text());
    lastSystem = now.text();
}
```

为什么删得掉：s09 那个版本号只覆盖了记忆。**待办看板变了它不会重拼**——因为它压根不知道待办也进了 system。判断「什么时候该拼」这件事，写得越细越容易漏；判断「拼出来一样不一样」，漏不了。

## 六个段

| 段 | 频率 | 来源 | 实测 token | rank |
| --- | --- | --- | --- | --- |
| `identity` | STATIC | 源码常量 | 51 | 10 |
| `tool-policy` | STATIC | 源码常量 | 199 | 20 |
| `workspace` | SESSION | 启动时探测 | 23 | 30 |
| `skills` | SESSION | 磁盘 `skills/*/SKILL.md` | 118 | 40 |
| `memory` | SESSION | 磁盘 `memory/*/MEMORY.md` | 754 | 50 |
| `todo` | ROUND | 内存里的待办看板 | 47 | 90 |

同是 SESSION，`skills` 排在 `memory` 前面，因为技能目录进会话就不动了，而记忆随时可能被写。同频段内也要按「谁更可能变」排——这条不移到前面，记忆一写，后面的技能目录跟着白费。

`workspace` 那段长这样：

```
Working directory: /…/core/s10-system · shell: /bin/zsh · session started 2026-11-21 13:40
```

会话开始时算一次，中途不再变。所以它能排在静态段后面，而不是每轮现算。

## 顺序值多少：实测 10 对请求

假端点不看程序打印的指纹，它自己量：把相邻两次请求的 system 全文逐字符比，算出**最长公共前缀（LCP）**。这就是缓存能命中的部分。

```
请求  system 字符  与上次相同  占比
   2       4174        4021  100.0%  ← 完全相同
   3       4174        4174  100.0%  ← 完全相同
   4       4174        4174  100.0%  ← 完全相同
   5       4174        1909   45.7%      ← 写了记忆：memory 段在中后部，它后面全部作废
   6       4174        4106   98.4%      ← 只改了待办：它在最后，前面一个字没动
   7       4174        4174  100.0%  ← 完全相同
   8       4173        3426   82.1%
   9       4175        3147   75.4%
  10       4175        4125   98.8%
  11       4175        4175  100.0%  ← 完全相同
```

11 个请求只拼出 **7 种** system；10 对相邻请求里 **4 对字节完全相同**，1 对是「前一次整条都被复用」（待办直接追加在尾部），3 对只差最后那几十个字符。

那一对 45.7% 是这套排序的代价上限：记忆目录 754 token 垫在末尾倒数第二位，它一变，后面只剩待办。如果按「哪个重要放前面」的直觉把记忆放第二段，这一对会变成 3%——把所有请求都拖到这个水平。

## 重拼不等于失效

程序里每轮都调 `buildPrompt()`，一共调了 12 次（1 次启动 + 11 轮），打「重写」日志的只有 6 次——另外 6 次拼出来的字节和上一轮一模一样，`messages[0]` 一个字节都没动：

```
   [prompt] 重写 messages[0]：变了 todo｜前缀 d28966ec 未变（缓存还能用）
   [prompt] 重写 messages[0]：变了 memory｜前缀 5afcfc56 变了
```

前半句是「这次拼出来的字节和上次不一样，换了哪几段」；后半句是「前缀指纹变没变」。

前缀指纹只覆盖**第一段 ROUND 之前**的内容。所以「待办变了」这一行，前缀是「未变」——缓存还能用；「记忆变了」这一行，前缀就变了，因为它本身就在前缀里。

```
   [prompt] 本会话 12 轮：前缀复用 8 次，重算 4 次；裁掉的段 workspace
```

## 预算：能砍的只有一段

`PROMPT_BUDGET` 默认 1200（估算 token）。超了按 `DROPPABLE` 顺序裁：

```
   [prompt] 4 段 → 1122 token（预算 1130）
            STATIC  identity      51 token
            STATIC  tool-policy  199 token
            SESSION skills       118 token
            SESSION memory       754 token
            ⚠ 超预算，裁掉：workspace（模型自己查得到的段，砍了不影响判断）
```

`DROPPABLE` 里只有 `workspace`。判断依据只有一条：**这段信息模型自己能不能拿到，而且拿到的还是最新的**。

- `workspace`：`pwd` 一条命令就有，随时是最新的 → 可砍。
- `todo`：tool 结果里也有一份，但那是**上一次写入时的快照**，中途状态变了它就是旧的。砍掉等于让模型按过期计划干活 → 不砍。
- `skills` / `memory`：一个是「你有这个能力」，一个是「上次踩过的坑」。砍了模型真不知道 → 不砍。

所以待办一出现，就会撞到这条线：

```
   [prompt] ⚠ 1169 token 超预算 1130：能裁的已经裁完，记忆目录和技能目录不裁，照发
```

报一次，然后照发。为了压到预算以下去砍记忆目录，是拿「下次别再犯」换「这轮省 700 token」——不划算，而且这种交换一旦写进代码，半年后没人记得为什么。

## 反例：把时间戳放在第一段

最直觉的写法是先告诉模型现在几点：

```java
sections.add(new Section("clock", Kind.STATIC, 0,
        "Current time: " + LocalDateTime.now().format(FULL_TS)));   // 毫秒精度
```

`PROMPT_MODE=naive` 就是这一版：时间戳置顶，其余不排序、按「重要性」排（记忆比技能重要，所以记忆在前）。

```
请求  system 字符  与上次相同  占比
   2       4229          34    0.8%
   3       4229          35    0.8%
   …
  11       4230          35    0.8%
```

11 个请求，**11 种 system，没有一对前缀能复用**。最长公共前缀稳定在 35 字符左右——正好卡在时间戳的秒上：

```
Current time: 2026-11-21T13:29:07.412 (Asia/Shanghai)
                                  ↑ 每次请求都不一样
```

两种写法的差距：

| | 按频率排序 | 时间戳置顶 |
| --- | --- | --- |
| 11 个请求拼出几种 system | 7 | 11 |
| 完全相同的相邻请求 | 4 / 10 | 0 / 10 |
| 平均可复用前缀 | 90.0% | 0.8% |
| 最长公共前缀 | 4175 字符 | 36 字符 |

要时间也不是不行：放到最后一段，或者只给日期。`2026-11-21` 一天变一次，放在 STATIC 段完全没问题；`13:29:07.412` 一秒变一次，放哪儿都在毁前缀。

## 验证输出（假端点，实际执行）

```
   [skill] 技能目录 3 项：[hotfix, release-notes, sql-review]
   [memory] 项目级 30 条（memory/project/MEMORY.md），用户级 5 条（memory/user/MEMORY.md）
   [prompt] 4 段 → 1122 token（预算 1130）
   [prompt] ⚠ 超预算，裁掉：workspace
→ todo_write([3 tasks])
   [prompt] 重写 messages[0]：变了 todo｜前缀 d28966ec 未变（缓存还能用）
→ memory_read("api-error-shape")
→ read_file({"path":"data/big-1.log"})
→ memory_write(project:api-error-shape)
   [prompt] 重写 messages[0]：变了 memory｜前缀 5afcfc56 变了
→ todo_write([3 tasks])
   [prompt] 重写 messages[0]：变了 todo｜前缀 5afcfc56 未变（缓存还能用）
→ read_file({"path":"data/big-2.log"})
→ memory_write(user:reply-style)
   [prompt] 重写 messages[0]：变了 memory｜前缀 18955589 变了
→ memory_write(project:log-format)
   [prompt] 重写 messages[0]：变了 memory｜前缀 386a7a39 变了
→ todo_write([3 tasks])
   [prompt] 重写 messages[0]：变了 todo｜前缀 386a7a39 未变（缓存还能用）
→ skill("sql-review")
   [prompt] 本会话 12 轮：前缀复用 8 次，重算 4 次；裁掉的段 workspace
```

十一项断言：

```
identity_first=YES            身份在最前，它是静态段
section_order=YES             技能目录 → 记忆目录 → 待办，变化频率递增
volatile_at_tail=YES          每轮可能变的待办段垫在最后
trim_dropped_workspace=YES    超预算，先裁掉 pwd 一句就能拿到的段
byte_identical_pairs=YES      4 对相邻请求字节完全相同
prefix_fully_covered=YES      另有 1 对：前一次整条被复用，新内容只追加在尾部
prefix_survives_round_change=YES  10 对里 9 对前缀还能用
distinct_systems_few=YES      11 个请求只拼出 7 种 system
budget_reported=YES           1174 越过预算 1130，报一次而不是砍记忆目录
skill_load_keeps_system=YES   加载技能只往 messages 加正文，system 没动
script_finished=YES           剧本 11 轮跑完
```

反例那一遍：

```
naive_clock_first=YES         秒级时间戳排在第一段
naive_memory_before_skills=YES  按重要性把 754 token 的记忆排在技能前面
naive_all_distinct=YES        11 个请求，11 种 system
naive_prefix_broken=YES       10 对相邻请求，没有一对前缀能复用
naive_lcp_short=YES           最长公共前缀 36 字符
```

`skill_load_keeps_system` 值得单独说一句：第 10 轮加载 `sql-review`，340 字符正文进了 `messages`，`system` 一个字节没动。技能目录早就常驻在 system 里了，加载动作只影响 `messages`——**所以「按需加载」省的是 messages 的预算，不是 system 的**。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 接口 | OpenAI 兼容：`OPENAI_BASE_URL` / `OPENAI_API_KEY` |
| system 预算 | `PROMPT_BUDGET`，默认 1200；实测设 1130（正好逼出一次裁剪） |
| 上下文预算 | `CONTEXT_LIMIT`，默认 8000；实测设 60000（本篇不触发压缩，免得摘要请求混进测量） |
| 拼接模式 | `PROMPT_MODE=sorted`（默认） / `naive` |
| 初始记忆 | 沿用 s09 的 `memory.seed/`：项目级 30 条、用户级 5 条 |
| 剧本 | 11 轮：写记忆 2 次、更新待办 3 次、读日志 2 次、读记忆 1 次、加载技能 1 次 |
| 测量方式 | 假端点对相邻请求的 system 全文算最长公共前缀，只统计带 `tools` 的请求 |

`PROMPT_BUDGET` 的具体值会影响「裁哪几段」：`workspace` 那段的长度随工作目录路径变，换台机器实测值会差十几个 token。本篇所有数字都出自上表这组参数。

## 怎么跑

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1
mvn -q clean compile
mvn -q exec:java
```

验证机制不花钱，用仓库里的假端点：

```sh
python3 fake-endpoint.py                 # 端口 8910，会把 memory.seed/ 复制成 memory/
CONTEXT_LIMIT=60000 PROMPT_BUDGET=1130 OPENAI_API_KEY=sk-fake \
  OPENAI_BASE_URL=http://127.0.0.1:8910/v1 MODEL_ID=fake-model mvn -q exec:java

PROMPT_MODE=naive python3 fake-endpoint.py     # 反例：同一份剧本，换个拼接顺序
PROMPT_MODE=naive CONTEXT_LIMIT=60000 PROMPT_BUDGET=1130 OPENAI_API_KEY=sk-fake \
  OPENAI_BASE_URL=http://127.0.0.1:8910/v1 MODEL_ID=fake-model mvn -q exec:java
```

## 简化说明

- **没有真正读缓存命中率**。LCP 是前端能测到的上限，实际命中多少还取决于服务端的缓存粒度（最小 token 数、TTL）。这里只比较「前缀有没有变」。
- **没有多轮复用别的会话**。前缀缓存的另一半价值是跨会话、跨进程复用：两个用户共享同一段静态前缀时，服务端只算一次。这里只测了单会话内的复用。
- **段的内容是手写的**。真实产品里 identity 和 tool-policy 背后是模板 + 变量插值，还有多语言。这里为了看清楚「哪段该放前面」，故意写成五个常量方法。
- **裁剪只到「整段」粒度**。真实做法更细：记忆目录超过 N 行就截断并提示「还有 12 条没显示，用 memory_search 找」。这里只做整段裁，因为它更容易讲清「砍谁」这条判断。
- **没有做 prompt 版本管理**。改了 identity 的那句话，怎么知道线上效果变差是因为它？真实产品要给每段打版本号、记录 A/B。这里只算指纹，不算版本。
