# s04 对照 · Spring AI 的 advisor 链

手写版 s04 用注册表 + `trigger(event)` 把扩展挂在循环周围。Spring AI 2.0 里这件事叫 advisor 链——这里用一个自定义 `LoggingAdvisor` 说明对应关系。对照 `core/s04-hooks` 看。

## 解决什么问题

手写 hook 要自己维护事件表、自己 register、自己在循环四个点显式 trigger。Spring AI 把"环绕调用前后插入逻辑"做成了 advisor：实现 `before()` / `after()`，加进链，框架每次调用自动环绕。这一对照回答：手写 hook 的前后环绕，在框架里对应到哪两个方法。

## 实验条件

| 项 | 值 |
|---|---|
| 模型 | deepseek-chat（可换任意 OpenAI 兼容端点） |
| temperature | 服务端默认，未在代码里指定 |
| 固定提示词 | `用一句话说明什么是 hook。` |
| advisor | 1 个自定义 LoggingAdvisor（before/after 各打印一行） |
| 说明 | 没有真实 key 时用一个只回文本的假端点，只演示 advisor 环绕链路；真实输出随模型版本漂移，固定以上条件才可对照片 |

## 手写版 vs 框架版

| 手写版 s04 | Spring AI 2.0 |
|---|---|
| `register(event, hook)` 挂回调 | `defaultAdvisors(new LoggingAdvisor())` 加进链 |
| hook 的「执行前」 | `BaseAdvisor.before(request, chain)` |
| hook 的「执行后」 | `BaseAdvisor.after(response, chain)` |
| 多个 hook 按注册顺序跑 | 多个 advisor 按 `getOrder()` 排序 |
| 循环显式 `trigger(event)` | 框架在每次 ChatClient 调用自动环绕 |

## 关键差异

手写的 hook 触发点是**你自己的循环**写的，挂在哪、什么时候触发一目了然。advisor 的触发点是**框架**在 ChatClient 调用链里安排的，你看不到循环——好处是省事，代价是失去"从零看见 hook 怎么挂"的过程。这正是先看 `core/` 手写版的意义。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com   # 不带 /v1，Spring AI 自己拼
export MODEL_ID=deepseek-chat
mvn -q spring-boot:run
```

> 版本：Spring AI 2.0.0，基线 Spring Boot 4.0 + Java 21。`BaseAdvisor` 包名 `org.springframework.ai.chat.client.advisor.api.BaseAdvisor`，`before`/`after` 是它的两个抽象方法。
