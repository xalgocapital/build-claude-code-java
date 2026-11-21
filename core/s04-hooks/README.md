# s04 · Hooks 钩子

把扩展逻辑挂在循环周围，而不是写进循环里。这一篇的全部新东西是一个注册表 `HookRegistry` 和四个挂点。

## 解决什么问题

s03 的权限检查是硬编码在循环里的一行 `checkAndApprove()`。再想加"记录每次 bash 调用""操作后自动 git add"，就得往 `while(true)` 里继续塞代码，循环很快膨胀成一坨。

Hook 的做法：循环只认 `trigger(event)` 一个动作，所有扩展逻辑在启动时 `register(event, hook)` 挂上去。加行为不动循环。

## 四个事件

| 事件 | 触发时机 | 典型用途 | 返回值语义 |
|---|---|---|---|
| UserPromptSubmit | 用户输入后、进 LLM 前 | 输入验证、注入上下文 | 返回值本版未用 |
| PreToolUse | 工具执行前 | 权限检查、日志 | 非 null = 阻止本次执行 |
| PostToolUse | 工具执行后 | 副作用、输出检查 | 返回值本版未用 |
| Stop | 循环即将退出时 | 收尾统计 | 非 null = 强制续跑 |

## 相对 s03 的变更

| | s03 | s04 |
|---|---|---|
| 权限检查 | 循环里 `guard.checkAndApprove()` 硬编码 | 注册在 PreToolUse 上的 `permissionHook()` |
| 执行前 | 直接调检查函数 | `hooks.trigger(Event.preTool(...))` |
| 日志 / 大输出提醒 / 统计 | 无 | 各自 register 到对应事件 |
| 退出控制 | 无 | Stop hook 返回非 null 可强制续跑 |

循环体结构没动——调用模型、扫 tool_calls、执行工具、喂回，还是那几段。

## 实验条件

| 项 | 值 |
|---|---|
| 端点 | DeepSeek `https://api.deepseek.com/v1`（可换任意 OpenAI 兼容端点） |
| 模型 | deepseek-chat |
| max_tokens | 8000 |
| temperature | 服务端默认 |
| 固定提示词 | `Read README.md, then create a test file.` |
| 注册的 hook | 5 个（contextInject / permission / log / largeOutput / summary） |

真实 API 的输出会随模型版本漂移，想复现就把上面几项固定住。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1   # 可选
export MODEL_ID=deepseek-chat                        # 可选
mvn -q exec:java
```

本地假端点端到端验证已跑通：一次返回三调用覆盖 PreToolUse 拦截（sudo 命中硬拒绝）、PreToolUse 放行、PostToolUse，被拒结果作为 tool 消息回灌模型（模型下一轮确认收到），Stop hook 统计工具调用数；`finish_reason` 故意撒谎 `stop` 仍靠扫 tool_calls 继续。未接真实模型 API（环境无 key）。

## 和真实 CC 的差距（诚实说明）

我们这个实现是 4 个事件、单一字符串返回值；真实 Claude Code 有 27 个 hook 事件、14 个字段的 `HookResult`（blockingError / updatedInput / additionalContext / permissionBehavior 等）。还有一条关键不变式：hook 返回 allow 也不能绕过 settings.json 的 deny/ask 规则——我们这个实现没这层。Stop hook 的 `stopHookActive` 防无限循环机制也省略了。这篇要证明的是"hook 挂在哪个点、怎么影响执行"，不是复刻完整的 hook 系统。
