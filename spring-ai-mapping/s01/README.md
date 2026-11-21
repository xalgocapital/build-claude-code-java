# s01 · Spring AI 2.0 对照

同一个最小循环，Spring AI 2.0 怎么写。对照 `core/s01-loop` 看，重点是**框架替你做了什么**。

## 解决什么问题

手写版那个 `while(true)` 循环，在 Spring AI 2.0 里已经内置成 advisor——你不再自己写循环，但得知道框架在哪儿替你转、转的停止条件是什么。这一对照回答：框架版的循环藏在哪、和手写版一一对应到哪几行。

## 实验条件

| 项 | 值 |
|---|---|
| 模型 | deepseek-chat（可换任意 OpenAI 兼容端点） |
| temperature | 服务端默认，未在代码里指定 |
| 固定提示词 | 见 `SpringAiLoopApp.java` 里的 user prompt |
| 说明 | 没有真实 key 时用一个只回文本的假端点，只演示 API 链路；真实输出随模型版本漂移，固定以上条件才可对照片 |

## 环境要求

| 项 | 版本 |
|---|---|
| Spring AI | 2.0.0（2026-06-12 GA，本系列实测钉在此版；2.1.0 已于 2026-11-20 GA） |
| Spring Boot | 4.0.0 |
| JDK | 21+ |

> Spring AI 2.1.0 已于 2026-11-20 GA（基线 Boot 4.2），advisor 链这套写法沿用；2.0 线由 2.0.3 继续维护。本模块钉 2.0.0，是实测过的版本。别拿 1.x 的写法套——2.0 把工具循环从 ChatModel 内部移到了 advisor 链，`internalToolExecutionEnabled` 那套已经删除。

## 手写版 vs 框架版

| 手写版要做的 | Spring AI 2.0 |
|---|---|
| `while(true)` 循环 | `ToolCallingAdvisor`（递归 advisor，重入链直到模型不调工具） |
| 工具 JSON Schema | `@Tool` 注解自动生成 |
| 解析 tool_calls、逐个执行 | `ToolCallingManager` |
| 结果回传 messages | advisor 内部维护 conversation history |
| 判断继续还是停 | advisor 的 stop condition（模型无 tool call） |

一句话：**框架把那 30 行包成了你看不见的一层。** 这就是为什么要先看手写版。

## 2.0 的关键变化

1.x 时工具循环藏在各个 ChatModel 内部，没法拦截、没法观测、没法组合。2.0 把它提升成 advisor 链里的一等公民：

- `ChatClient` 自动注册 `ToolCallingAdvisor`
- advisor 是有序链，其他 advisor（memory、日志、权限）可以放在循环内或外
- 可以 opt out，自己用 `ChatModel` + `ToolCallingManager` 驱动循环——这就退回手写版了

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com   # 注意这里不带 /v1，Spring AI 自己拼
export MODEL_ID=deepseek-chat
mvn -q spring-boot:run
```

## 什么没对照

Spring AI 2.0 能覆盖的章节有限（见仓库根 README 的索引）。s01/s02/s04/s08/s19 有直接对应；s12-s18 的任务、团队、自治、worktree 它没有官方实现，那些章只写手写版。
