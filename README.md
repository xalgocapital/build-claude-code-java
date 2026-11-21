# 从零重造 Claude Code · Java 手写实现

用 Java 从零重造一个 Claude Code-like agent，拆成 20 个渐进版本。每次只加一个机制，循环本身始终不变。

配套公众号系列《从零重造 Claude Code》。

## 仓库结构

```
core/                     每章手写实现，主线，不依赖任何 Agent 框架
  s01-loop/               Agent 循环（最小内核）
  s02-tools/              工具分发表
  s03-permission/         三道权限闸门
  s04-hooks/              四个挂点 + 注册表（已落地）
  s05-todo/               TodoWrite 三态清单 + nag reminder（已落地）
  s06-subagent/           子代理：独立 messages[] + 只回传结论（已落地）
  s07-skill/              技能按需加载：目录常驻 + 正文调用时才进上下文（已落地）
  s08-compact/            上下文压缩：切点守 tool_call_id 配对，摘要 + 活状态回贴（已落地）
  s09-memory/             跨会话记忆：两级文件 + 目录常驻 / 正文按需 + 四条防脏（已落地）
  s10-system/             system prompt 分段拼装：按变化频率排序 + 只比字节 + 预算裁剪（已落地）
  s11-recovery/           错误恢复：三类失败 + 等值抖动重试 + 工具错误回灌 + 死循环检测（已落地）
  s12-task/               任务系统：任务落盘 + blockedBy 依赖 + 认领 + 重启降级孤儿（已落地）
  s13-bg/                 后台任务：输出落盘 + offset 增量读 + 完成通知走 messages（已落地）
  s14-cron/               定时任务：手写 cron 表达式 + 调度时钟 + 错过不补跑记 skipped（已落地）
  s15-team/               多代理团队：一人一个收件箱 + 轮转 tick + 认领只有一个 owner（已落地）
  s16-protocol/           团队协议：信封四型 + replyTo 配对 + 超时死信 + handoff 表态（已落地）
  s17-auto/               自主代理：按 priority 挑活 + 独立会话干活 + 三个信号记账 + 连续无进展就停（已落地）
  s18-worktree/           工作树隔离：一人一棵 git worktree + 工具绑 cwd + 冲突不吞、主树不动（已落地）
  s19-mcp/                MCP 工具接入：stdio JSON-RPC + id 配对 + 超时 + 重连（已落地）
  s20-agent/              总装：显式装配清单 Boot + 预算裁剪 tasks→workspace + status 仪表盘（已落地）
  ...
spring-ai-mapping/        Spring AI 2.1 对照实现，只挑框架真有对应的章
  s01/                    ToolCallingAdvisor 实现同样的循环（已落地）
  s02/                    @Tool 定义工具（已落地）
  s04/                    自定义 BaseAdvisor = hooks（已落地）
  s08/                    CompactionAdvisor + MessageWindowChatMemory 对照（已落地）
  s19/                    MCP client 接同一个 server：传输/握手/工具注册交给 starter（已落地）
```

## 为什么要分两块

`core/` 是这个系列的主线：手写循环、手写工具分发、手写权限。它回答"Claude Code 的原理是什么"。

`spring-ai-mapping/` 是流量入口和对照视角：同一个机制，Spring AI 2.1 里叫什么、怎么写。它回答"生产里框架替你做了什么"。对照代码用 Spring AI 2.1.0（2026-11-20 GA，基线 Boot 4.2）实测；`ChatMemory` 在 2.1 已标废弃（3.0 移除，改推 Session API），见 s08 对照的说明。

**不要用 `spring-ai-mapping/` 替代 `core/`。** Spring AI 的 `ToolCallingAdvisor` 本身就是 agent 循环——用它当主线，你看不到循环长什么样，这个系列也就没有存在的意义了。

## 环境要求

- JDK 21+
- Maven 3.9+
- 一个 OpenAI 兼容端点的 API key（DeepSeek / 通义 / Kimi / GLM 等，真实调用，不提供 mock）

core 模块用原生 `java.net.http.HttpClient` + Jackson 直连 OpenAI Chat Completions 兼容端点，不引入任何 Agent 框架，保证每一行机制都看得见。

为什么不用 Anthropic 原生 API：国内读者申请 key 门槛高、实验复现不了。OpenAI 兼容协议下，DeepSeek / 通义 / Kimi / GLM 都支持 tool calling，换 base_url + key + model 三个值就能切。**机制与 API 方言无关，换的只是最外层适配器。**

## 运行

以 s01 为例：

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可选
export MODEL_ID=deepseek-chat                        # 可选

cd core/s01-loop
mvn -q exec:java
```

> 真实 API 会产生费用。s01 会执行模型生成的 shell 命令，请在临时目录运行。权限机制要到 s03 才加。
>
> 请求体里用 `max_tokens`，DeepSeek / 通义 / Kimi / GLM 都吃这个字段。若切到 OpenAI o 系列 / GPT-5 等新模型报 400，把它改成 `max_completion_tokens` 即可，机制部分不受影响。

## 实验条件

每篇的实验条件写在该章 README 的「实验条件」一节：模型版本、temperature、固定提示词，固定这些才能对照。

## 章节索引

| 章 | 主题 | core | Spring AI 对照 |
|---|---|---|---|
| s01 | Agent Loop | ✅ | ✅ ToolCallingAdvisor |
| s02 | Tool Use | ✅ | ✅ @Tool |
| s03 | Permission | ✅ | — |
| s04 | Hooks | ✅ | ✅ 自定义 advisor |
| s05 | TodoWrite | ✅ | — |
| s06 | Subagent | ✅ | — |
| s07 | Skill Loading | ✅ | — |
| s08 | Context Compact | ✅ | ✅（`spring-ai-mapping/s08`） |
| s09 | Memory | ✅（`core/s09-memory`） | — |
| s10 | System Prompt | ✅（`core/s10-system`） | — |
| s11 | Error Recovery | ✅（`core/s11-recovery`） | — |
| s12 | Task System | ✅（`core/s12-task`） | — |
| s13 | Background Tasks | ✅（`core/s13-bg`） | — |
| s14 | Cron Scheduler | ✅（`core/s14-cron`） | — |
| s15 | Agent Teams | ✅（`core/s15-team`） | — |
| s16 | Team Protocols | ✅（`core/s16-protocol`） | — |
| s17 | Autonomous Agents | ✅（`core/s17-auto`） | — |
| s18 | Worktree Isolation | ✅（`core/s18-worktree`） | — |
| s19 | MCP Tools | ✅（`core/s19-mcp`） | ✅ MCP client（`spring-ai-mapping/s19`） |
| s20 | Comprehensive Agent | ✅（`core/s20-agent`） | — |
