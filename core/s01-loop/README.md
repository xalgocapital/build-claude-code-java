# s01 · Agent Loop

最小 Agent 内核：一个 `while(true)`，模型决策、harness 执行，结果喂回去。用 OpenAI 兼容协议，国内模型都能跑。

## 解决什么问题

模型只会输出命令，不会自己跑、看结果、再推理。人肉复制粘贴当中间层。这个循环把中间层自动化。

## 为什么用 OpenAI 兼容协议

模型调用用 OpenAI Chat Completions 格式，没用 Anthropic Messages API，理由：

- 国内读者申请 Anthropic key 门槛高，实验复现不了。DeepSeek / 通义 / Kimi / GLM 都提供兼容端点且支持 tool calling
- 换 base_url + key + model 三个值就能切换模型，方便做对照实验
- 和 Spring AI 生态天然契合

**机制不变，换的只是最外层的 HTTP 适配器。** 循环、工具分发、权限这些道理与 API 方言无关。

## 核心代码

`src/main/java/com/xalgocapital/claude/s01/AgentLoop.java`

```
messages = [system, user(query)]
while true:
    response = client.createChatCompletion(messages, tools)
    messages.add(response.message)          # 整条回传，带 tool_calls
    if 没有 tool_calls: 打印回答, return
    for 每个 tool_call: 执行, 结果作为 role=tool 消息追加
```

## 两个协议差异，都值得单独说

**1. 不看 finish_reason，扫 tool_calls。** Claude Code 判断要不要继续时不看 `stop_reason`，而是扫消息内容里有没有 `tool_use` 块——流式响应里那个字段可能还没更新。OpenAI 兼容层里这个坑更大：`finish_reason` 的字符串各家不统一。所以代码里直接扫 `tool_calls` 数组判断要不要继续，更稳。

**2. arguments 类型不统一。** OpenAI 返回 JSON 字符串，有些兼容后端返回已解析对象。`parseArgs()` 两种都吃，盲 parse 会崩。

工具定义格式也不同：OpenAI 是 `tools[].function.parameters`，Anthropic 是 `tools[].input_schema`。

## 实验条件

| 项 | 值 |
|---|---|
| 端点 | `https://api.deepseek.com/v1`（`OPENAI_BASE_URL` 可改） |
| 模型 | `deepseek-chat`（`MODEL_ID` 可改） |
| max_tokens | 8000 |
| temperature | 不显式设置，用服务端默认 |
| system prompt | 见 `AgentLoop.SYSTEM` |
| 固定提示词 | `List the files in the current directory, then tell me how many there are.` |

> 改动任何条件后，观察结论可能不同。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可选，默认这个
export MODEL_ID=deepseek-chat                        # 可选，默认这个
mvn -q exec:java
# 或带自己的提示词
mvn -q exec:java -Dexec.args="Create a file called hello.py that prints hello"
```

切到别的兼容模型（改 base_url + model 两个值即可）：

```sh
# 通义千问
export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export MODEL_ID=qwen-plus
# Kimi
export OPENAI_BASE_URL=https://api.moonshot.cn/v1
export MODEL_ID=kimi-k2
```

## 观察重点

- 模型什么时候调工具（循环继续），什么时候不调（退出）
- messages 数组每一轮怎么增长——assistant 消息和 tool 结果都追加进去
- 一个 prompt 里模型可能连续吐多个 tool_calls，循环逐个执行

## 安全

s01 会真实执行模型生成的 shell 命令，**没有任何权限检查**。请在临时目录运行。权限机制在 s03 才加。
