# s20 · Comprehensive Agent

十九篇里每一篇的 README 都可以说「已跑通」。把十九个机制装进同一个构造函数，
「跑通」就不够了——它们抢同一块预算，而装配顺序没人看得见。

这一篇做三件事：把装配顺序变成一张显式的表（`Boot`），把预算冲突实测出来
（STATIC / SESSION / ROUND 三段在预算压穿时的不同命运），给模型自己一个仪表盘
（`status` 工具）。外加一个反例：`BOOT_MODE=naive` 按字母序装配，
压缩摘要里悄悄少掉 35 条记忆。

## 十九个机制，装在一起之后

| 机制 | 哪一篇 | 单独看 | 装在一起之后 |
|---|---|---|---|
| 主循环 | s01 | while(true) 能跑 | 10 个子系统都往循环里挂东西，谁先谁后没人说得清 |
| 工具分发 | s02 | 注册一次加一个工具 | 本地 13 + 远端 3 张表混装，status 之前没人知道总数 |
| 权限 | s03 | deny / ask 两道闸 | 依赖技能预授权（s07），构造顺序错了拿到 null |
| Hooks | s04 | 事件回调 | PreToolUse 要读技能表，hooks 排在 skills 前面就崩 |
| 待办看板 | s05 | 提醒节奏 NAG_AFTER | 看板进 system 的 ROUND 段，预算压穿时第一个被裁 |
| 子代理 | s06 | 独立上下文 | 依赖 hooks，第 6 个装配 |
| 技能加载 | s07 | 目录常驻正文按需 | 目录 118 token 常驻，裁剪队列里没有它 |
| 上下文压缩 | s08 | 摘要顶替旧消息 | 摘要请求要读记忆索引——依赖 memory，naive 装配下这条边断了 |
| 跨会话记忆 | s09 | 索引常驻 754 token | 占 system 预算最大的一块，但不进裁剪队列 |
| system 分段 | s10 | STATIC/SESSION/ROUND | 裁剪队列 s10 只排了 workspace，s20 补齐成 tasks → workspace |
| 错误恢复 | s11 | 重试分类 | 400 超长触发 forceCompact，压缩又依赖 memory |
| 任务落盘 | s12 | .tasks/*.json | 装配表里「磁盘 3 个任务」是启动时读盘读出来的 |
| 后台任务 | s13 | 不阻塞循环 | 不在这条装配线上（s13 模块独立），status 给它留了位置 |
| 定时调度 | s14 | cron 触发 | 同上 |
| 团队 | s15 | 多 agent 分工 | 同上 |
| 团队协议 | s16 | 消息约定 | 同上 |
| 自主代理 | s17 | 长程自治 | 预算问题在长程任务里最先爆，这篇的实验就是它的前传 |
| worktree 隔离 | s18 | 并行工作区 | 同上 |
| MCP | s19 | 工具在进程外 | 装配表 mcp 行「远端 3 个」，握手 clientInfo=lcc-s20 |

s20 的构造函数实际装配 10 个子系统，覆盖 s01–s12 加 s19 的机制；
s13–s18 在各自模块里，装配表这个形式是给它们准备的——多一个子系统就是多一行声明。

## 装配清单：Boot

```java
steps.add(new Boot.Step("compact", List.of("memory"), () -> {
    this.compactor = new Compactor(client, this::liveState, memory);
    return new Boot.Item("compact", List.of("memory"), 0, "预算 " + SystemPrompt.budget(), true);
}));
```

每一行是 `{name, dependsOn, tokens, ready}`。`Boot.order()` 按依赖拓扑排序，
声明顺序是并列时的先后；`Boot.wire()` 排序、逐个构建、打印整张表。
`AgentLoop` 的构造函数只剩一件事：把 10 个 Step 交给 Boot。

实测的装配表（`SCENARIO=main`，播种 3 个磁盘任务 + 35 条记忆）：

```
[boot] 10 个子系统，按依赖顺序启动
  tools     ←  无                 5 个基础工具
  tasks     ←  tools             磁盘 3 个任务 / 43 token
  skills    ←  tools             技能 3 项 / 118 token
  memory    ←  tools             35 条（project 30 / user 5） / 754 token
  hooks     ←  skills            6 个钩子
  compact   ←  memory            预算 1100
  subagent  ←  hooks             子代理 1 层
  prompt    ←  tasks,skills,memory identity/policy/workspace / 299 token
  mcp       ←  tools             远端 3 个
  status    ←  tasks,skills,memory,compact,prompt,mcp 仪表盘
[boot] 装配表合计 1214 token（进 system 的部分），10/10 就绪
```

1214 是装配时的估算；实际首个请求的 system 是 1148 token（预算压穿，
tasks 和 workspace 两段被裁掉了）。差额就是被裁的那两段。

## 预算冲突：先砍谁

`PROMPT_BUDGET=1100`，刚好低于 1214 的装配总量。s10 的裁剪队列只有 workspace 一段；
s20 把它补齐，排序依据只有一条：**模型自己能不能拿回这段信息**。

| 段 | 频率 | 实测命运 | 依据 |
|---|---|---|---|
| identity / tool-policy | STATIC | 一个 token 都没动 | 裁掉它模型连规则都不知道了 |
| tasks（任务板） | ROUND | 第一个被裁 | 唯一有专用读取工具的段（task_list） |
| workspace | SESSION | 第二个被裁 | pwd 一条命令 |
| skills / memory 目录 | SESSION | 不裁，照发（超预算警告打出来） | 砍了就真不知道了 |

```
   [prompt] ⚠ 1148 token 超预算 1100：能裁的已经裁完，记忆目录和技能目录不裁，照发
   [prompt] 4 段 → 1148 token（预算 1100）
            STATIC  identity      51 token
            STATIC  tool-policy  225 token
            SESSION skills       118 token
            SESSION memory       754 token
            ⚠ 超预算，裁掉：tasks, workspace（模型自己查得到的段，砍了不影响判断）
```

代价要如实说：任务板被裁之后，**所有请求的 system 里都没有看板**（断言逐请求验过）。
模型丢的是「现在该做哪个任务」的常驻视野，能不能找回来取决于它自己想起来调
`task_list`——实测里调一次，任务清单就回来了。s10 不裁 tasks 的理由（快照会过期）
到 s20 依然成立，只是预算真的不够时必须给出顺序：可恢复的先裁，
不可恢复的宁可贵也要留。

## status：给模型自己的仪表盘

装配表是启动时打给人看的。会话跑起来之后，状态在模型那边是黑盒。
`status` 工具一次返回所有子系统：

```
→ status({})
[status]
boot: 10/10 就绪（tools,tasks,skills,memory,hooks,compact,subagent,prompt,mcp,status）
tasks: 5 个，可开工 2
tools: 16 个（本地 13 + mcp 3）
skills: 3 项，已加载 0
memory: 35 条（project 30 / user 5）
compact: 压缩 1 次，省 1769 token
prompt: 预算 1100，前缀复用 9 次，重算 1 次，裁掉的段 tasks,workspace
mcp: server demo，进程 1，重连 0，超时 0
```

端点逐字段核过：任务数和磁盘上的 5 个 .json 一致，记忆数和 memory.seed 的
35 条一致，mcp 3 个和请求 tools 数组里的远端工具一致，压缩次数和 stdout 里的
`[compact]` 事件数一致。模型在被裁掉看板之后调一次 status，
就能知道「系统把我裁了什么」。

## 反例：BOOT_MODE=naive

`BOOT_MODE=naive` 让 `Boot.order()` 按字母序排列，不看依赖：

```
  compact   ←  memory            预算 1100
  hooks     ←  skills            6 个钩子
  mcp       ←  tools             远端 3 个
  memory    ←  tools             35 条（project 30 / user 5） / 754 token
  ...
```

compact 排到了 memory 前面。`Compactor` 在构造时抓住 memory 引用，
这时它是 null——而且之后没有人会再提醒。压缩照常触发（端点回 400，
客户端照常强制压缩），摘要请求里本该带的 35 条记忆索引一条都不在：

```
正确装配序：摘要请求里 35/35 条记忆索引都在
naive 装配序：摘要请求里 0/35 条记忆索引——少整 35 条
```

难发现的点实测也钉住了：system 里的记忆目录还在（那是 buildPrompt 运行期读的），
system 分段照常，前缀缓存照常，10 轮剧本跑完不崩。它不报错，它只是悄悄错。
**装配顺序不是审美，是会把数据弄丢的东西。**

## 实测

10 轮剧本（端口 8920，`PROMPT_BUDGET=1100`、`CONTEXT_LIMIT=12000`）：
建 2 个任务 → 读 1 条记忆 → 远端 echo 三段文本（中、中、大）→ 端点回 400
强制压缩 → status → task_list → 收尾。

```
   [compact] 第 1 次压缩：11 条 → 1 条摘要，上下文 7953 → 6184 token（估算），摘要 177 字符
   [prompt] 本会话 12 轮：前缀复用 11 次，重算 1 次；裁掉的段 tasks,workspace
   [memory] 磁盘上 35 条：目录 34 行 ≈ 721 token，全文合计 ≈ 2160 token
   [task] 磁盘上 5 个任务：完成 1，可开工 2
   [boot] 10/10 个子系统就绪：tools,tasks,skills,memory,hooks,compact,subagent,prompt,mcp,status
```

main 场景 18 项断言全 YES：

```
boot_table_printed=YES             装配表 10 行，名字一个不少
boot_topo_order=YES                声明序即拓扑序，memory 在 compact 前
boot_tokens_near_system=YES        合计 1214 vs 实际 system 1148，差的是被裁的段
static_never_trimmed=YES           identity / tool-policy 首尾逐字节相同
trim_order_round_first=YES         裁掉的是 tasks 然后 workspace
tasks_board_absent_from_system=YES 所有请求 system 里都没有看板，丢进度是真的
board_recoverable_via_task_list=YES task_list 还在，调一次清单就回来
overflow_forced_compaction=YES     端点回 1 次 400，强制压缩 1 次
compaction_once=YES                全会话只压 1 次
summary_request_has_memory=YES     摘要请求 35/35 条记忆索引
tool_pairing_intact=YES            12 个 POST 配对完整
status_fields_complete=YES         status 字段齐全
status_tasks_match_disk=YES        5 个任务 = 磁盘 5 个 .json
status_memory_matches_seed=YES     35 条 = memory.seed 35 条
status_mcp_matches_request=YES     mcp 3 = tools 数组 3 个远端工具
status_prefix_reuse_plausible=YES  status 快照 9 不大于收尾 11
mcp_handshake_present=YES          clientInfo=lcc-s20，装配表 mcp 行「远端 3 个」
loop_survived=YES                  剧本 10 轮走完
```

naive 场景 8 项断言全 YES（`/tmp/s20_assert_naive.txt`）：
`boot_mode_naive` / `boot_order_alphabetical` / `memory_after_compact` /
`summary_missing_memory`（0/35，少整 35 条）/ `system_memory_still_there` /
`prompt_assembly_unaffected` / `naive_compaction_still_works` / `naive_loop_survived`。

另有 DeepSeek deepseek-chat 真实 API 的最小会话验证，见
`build-claude-code-java/REAL-API-VERIFICATION.md`。

## 这一篇没解决的

- **装配表只管启动**。运行期没有子系统会下线（除了 MCP 进程死了会重连），没有热插拔。
- **裁掉的看板靠模型自觉找回**。系统不会在裁掉 tasks 的时候主动提醒模型「任务板不在了」，
  只靠 status 和 task_list 可用。真实产品可以在裁剪发生时注入一条提示，这篇没做。
- **依赖图没有校验工具**。`Boot.order()` 会在环上抛异常，但「这个依赖该不该存在」
  还是靠人读注释。
- **tokens 仍是估算**。汉字一个、英文词一个的粗估（s08 的那套），和真实 tokenizer 有偏差；
  断言全部写成区间关系。
- **s13–s18 不在这条装配线上**。对照表是机制级的，不是代码级的。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 子系统数 | 10（tools / tasks / skills / memory / hooks / compact / subagent / prompt / mcp / status） |
| system 预算 | `PROMPT_BUDGET=1100`（装配合计 1214，首个请求 1148） |
| 上下文预算 | `CONTEXT_LIMIT=12000`（触发阈值 0.75；本剧本压缩由端点 400 强制） |
| 裁剪队列 | tasks → workspace（STATIC 永不裁） |
| 故障注入 | 第 8 个剧本位回 400 context_length_exceeded |
| 反例 | `BOOT_MODE=naive`，字母序装配，摘要少 35 条记忆 |
| 剧本 | 10 轮：建任务 ×2 / 读记忆 / echo 中中大 / add / 400+压缩 / status / task_list / 收尾 |

## 跑一遍

```sh
cd build-claude-code-java/core/s20-agent
SCENARIO=main python3 fake-endpoint.py &

MCP_SERVER="python3 mcp-server.py" MCP_LOG=/tmp/s20_mcp.log \
  PROMPT_BUDGET=1100 CONTEXT_LIMIT=12000 \
  OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8920/v1 \
  MODEL_ID=fake mvn -q exec:java | tee /tmp/s20_stdout.log

cat /tmp/s20_assert.txt

# 反例：换一个端口，加 BOOT_MODE=naive
SCENARIO=naive FAKE_PORT=8921 python3 fake-endpoint.py &
BOOT_MODE=naive MCP_SERVER="python3 mcp-server.py" PROMPT_BUDGET=1100 CONTEXT_LIMIT=12000 \
  OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8921/v1 \
  MODEL_ID=fake mvn -q exec:java | tee /tmp/s20_stdout_naive.log

cat /tmp/s20_assert_naive.txt
```
