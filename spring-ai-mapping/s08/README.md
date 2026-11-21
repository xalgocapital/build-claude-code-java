# s08 对照 · Spring AI 的 advisor 与窗口记忆

手写版 s08 在循环开头调 `compactor.maybeCompact(messages)`。Spring AI 2.0 里没有「循环」给你插，但每次 `ChatClient` 调用都会穿过 advisor 链——所以在 `before()` 里改写 `request.prompt()`，效果完全一样。这里还顺手测了框架自带的 `MessageWindowChatMemory`，看清「窗口」和「摘要」不是一回事。

## 解决什么问题

上下文满了要处理，这件事在框架里落在哪？答案有两半：

1. **框架只给你窗口**：`MessageWindowChatMemory` 按条数保留最近的，超了直接丢，不留摘要。
2. **「压成摘要」得自己写**：框架没有内置摘要记忆（2.0 只剩 `MessageWindowChatMemory` 一个实现），要写成 advisor。

这一对照回答：手写的「每轮检查 + 就地改数组」，在框架里对应到 `before()` + `request.mutate()`。

## 实验条件

| 项 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（可换任意 OpenAI 兼容端点） |
| 预算 | 300 token（故意设得很小，让 14 条历史能顶穿） |
| 历史 | system + 目标 + 6 轮 `read_file` 工具调用，共 14 条 |
| 尾巴保留 | 4 条 |
| 摘要模型 | 同一个模型，另建一个不带 advisor 的 `ChatClient` |
| 说明 | 未接真实 API 时可跑仓库里的假端点，验证的是链路而不是模型输出 |

## 手写版 vs 框架版

| 手写版 s08 | Spring AI 2.0 |
|---|---|
| 循环开头 `compactor.maybeCompact(messages)` | `BaseAdvisor.before(request, chain)` |
| `messages.removeAll()` + `addAll(rebuilt)` 就地改 | `request.mutate().prompt(new Prompt(rebuilt)).build()` |
| 自己按字符估 token | 一样自己算，框架不给 token 计数 |
| 自己扫 `tool_calls` 判断切点 | 一样自己判断，只是换成 `AssistantMessage.hasToolCalls()` |
| 自己调模型生成摘要 | 一样自己调，注意别和主客户端共用同一个 `Builder` |
| 待办清单 / 已加载技能回贴 | 没有对应物，得自己在 advisor 里拼 |
| — | `MessageWindowChatMemory`：只有丢弃式窗口 |

## 关键差异

**框架省掉的是「在哪里挂」，不是「怎么压」。** 触发判断、切点选择、摘要生成、活状态回贴——这四件事一件都没少，只是从循环里挪到了 `before()` 里。压缩本来就是策略问题，不是接线问题。

**窗口记忆不是压缩。** 实测：同一段 14 条带工具调用的历史喂给 `MessageWindowChatMemory(maxMessages=4)`，最后只剩 1 条 `[SYSTEM]`——一个「轮」在数组里是 `assistant(tool_calls)` + `tool` 两条，按条数截断时整轮被挤出去，原始目标也没了。换成 18 条纯文本历史才留下 4 条。它便宜、无额外模型调用，代价是丢掉的对话连摘要都没有。

**别在同一个 Builder 上叠 advisor 再回头建第二个客户端。** `defaultAdvisors()` 改的是 Builder 自己，两个客户端共享同一份 advisor 列表，摘要调用会被压缩 advisor 再拦一次。第一次跑就撞上了，日志里多出一行「563 token 已超预算，但找不到安全切点」——那是摘要请求在被自己压。改用 `plain.mutate()` 分叉才对。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com   # 不带 /v1，Spring AI 自己拼
export MODEL_ID=deepseek-chat
mvn -q compile
mvn -q exec:java -Dexec.mainClass=dev.lcc.springai.s08.SpringAiCompactApp -Dexec.classpathScope=runtime
```

不花钱跑法：

```sh
python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8909 MODEL_ID=fake-model \
  mvn -q exec:java -Dexec.mainClass=dev.lcc.springai.s08.SpringAiCompactApp -Dexec.classpathScope=runtime
```

## 实测输出（假端点，实际执行）

```
输入历史：14 条，约 1029 token（估算）
   [advisor] 压缩：14 条 → 7 条，1029 → 426 token（估算）

最终回答: 按摘要继续：最慢三条已在摘要里，接着写 notes/verdict.txt。

=== MessageWindowChatMemory(maxMessages=4) ===
带工具调用的历史：喂进去 14 条，留下 1 条 [SYSTEM]
  原始目标还在吗：false
纯文本历史：喂进去 18 条，留下 4 条 [SYSTEM, USER, ASSISTANT, USER]
  原始目标还在吗：false
```

假端点收到的最终请求：

```
[fake] 摘要请求：收到 2 条消息，返回固定摘要
[fake] 正常请求：7 条消息 ['system', 'user', 'user', 'assistant', 'tool', 'assistant', 'tool']
[fake]   上下文里有摘要，摘要是 164 字符
```

7 条 = system + 原始目标 + 摘要 + 两轮工具调用。注意第 4 条是 `assistant` 而不是 `tool`——尾巴没有切在工具调用中间，`tool_call_id` 配对完好。

> 版本：Spring AI 2.0.0，基线 Spring Boot 4.0 + Java 21。`BaseAdvisor` 包名 `org.springframework.ai.chat.client.advisor.api.BaseAdvisor`；`ChatMemory` 实现在 `org.springframework.ai.chat.memory`。
