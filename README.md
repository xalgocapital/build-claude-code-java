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
  ...
spring-ai-mapping/        Spring AI 2.0 对照实现，只挑框架真有对应的章
  s01/                    ToolCallingAdvisor 实现同样的循环（已落地）
  s02/                    @Tool 定义工具（已落地）
  s04/                    自定义 BaseAdvisor = hooks（已落地）
  （s08 ChatMemory / s19 MCP 为规划项，对应篇章写完后再补对照）
```

## 为什么要分两块

`core/` 是这个系列的主线：手写循环、手写工具分发、手写权限。它回答"Claude Code 的原理是什么"。

`spring-ai-mapping/` 是流量入口和对照视角：同一个机制，Spring AI 2.0 里叫什么、怎么写。它回答"生产里框架替你做了什么"。

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

每篇的实验条件写在该章 README 的「实验条件」一节：模型版本、temperature、固定提示词。真实 API 的输出会随模型版本漂移，固定这些才能对照。

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
| s08 | Context Compact | 待更 | 计划 |
| s09 | Memory | 待更 | 计划 |
| s10 | System Prompt | 待更 | — |
| s11 | Error Recovery | 待更 | 计划 |
| s12 | Task System | 待更 | — |
| s13 | Background Tasks | 待更 | — |
| s14 | Cron Scheduler | 待更 | — |
| s15 | Agent Teams | 待更 | — |
| s16 | Team Protocols | 待更 | — |
| s17 | Autonomous Agents | 待更 | — |
| s18 | Worktree Isolation | 待更 | — |
| s19 | MCP Tools | 待更 | 计划 |
| s20 | Comprehensive Agent | 待更 | — |
