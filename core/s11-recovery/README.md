# s11 · Error Recovery

s10 把 system prompt 排好了序，省下的是「相同前缀不用重算」。这一篇问的是另一半：
**请求挂了，那段前缀还发得出去吗？**

## 问题

前十篇的循环有一个隐含假设：`createChatCompletion` 要么返回，要么不该返回。
真实端点不是这样：

- 429 限流、503 上游抖动、连接被重置——一分钟里能撞上三次
- 响应是 200，但 body 半截（流断了、代理截了）——不是 HTTP 错误，是解析失败
- 上下文超长——服务端直接 400，可你的估算器说「还没到阈值」
- 工具抛了个空指针——整个循环跟着一起死
- 模型原地打转，同一个调用发五遍，每遍都觉得自己在尝试

这些都不是「模型不够聪明」，是工程上一定发生的事。
把它们全塞进一个 `catch (Exception e) { throw new RuntimeException(e); }`，
最常见的结果是：401 被当成网络抖动重试四次，最后报「重试 4 次仍失败」，真正的原因埋在最里面。

## 相对 s10 的改动

| 文件 | 改了什么 |
|---|---|
| `Failure.java` | 新增。把失败分成 TRANSIENT / CONTEXT_OVERFLOW / FATAL 三类 |
| `RetryPolicy.java` | 新增。指数退避 + 等值抖动 + 听 Retry-After |
| `LoopDetector.java` | 新增。连续相同调用 → 第 3 次提醒、第 5 次中止 |
| `ApiException.java` | 新增。异常带「哪一类、能不能重试、让等多久」 |
| `OpenAICompatClient.java` | 重写。重试收进客户端，但对 OVERFLOW 不重试，抛给循环 |
| `Compactor.java` | 加 `forceCompact()`：被服务端拒了就别再信自己的估算 |
| `AgentLoop.java` | 工具异常转成 tool 消息；循环检测；超长时压缩后重发 |
| `fake-endpoint.py` | 重写。主动注入 429 / 503 / 半截 JSON / 400 超长 |

## 三类失败

```java
public static Failure of(int status, String body) {
    if (body.contains("context_length_exceeded") || ...) return CONTEXT_OVERFLOW;
    if (status == 408 || status == 429 || status >= 500) return TRANSIENT;
    return FATAL;
}
```

分类标准只有一个：**重试有没有意义**。不是「错得多严重」。

- `TRANSIENT`：同一份请求原样再发一次，可能就过了。
- `CONTEXT_OVERFLOW`：原样重试一万次也一样长。得先压缩，这是循环才有的能力。
- `FATAL`：401 / 400 参数错。重试只是在烧钱。

上下文超长在很多兼容端点上返回的也是 400，和「参数写错了」同一个码，所以必须看 body。
只看状态码的实现，会把超长当成参数错直接放弃——而它其实是可救的。

## 退避：为什么是等值抖动

```java
long raw = baseMs * (1L << (attempt - 1));
long capped = Math.min(raw, capMs);
long half = capped / 2;
return half + ThreadLocalRandom.current().nextLong(capped - half + 1);
```

指数退避解决「别把服务端打死」，抖动解决「别把服务端同时打死」：
十个客户端在同一毫秒被 429，退避都是 1s，那就在同一毫秒再来一次。

用等值抖动（`[delay/2, delay]`）而不是满抖动（`random(0, delay)`），是因为满抖动的间隔不可预测，
「第三次比第二次等得久」这种断言写不出来。留一半固定值，既错峰又单调。

`Retry-After` 优先：服务端说等 1 秒就等 1 秒，不按自己的节奏走。
服务端比客户端更清楚自己什么时候缓过来。

## 重试发的是同一份 messages

```java
for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
    try {
        return once(messages, tools);      // 同一个 messages 引用
    } catch (ApiException e) { ... }
}
```

没有重建数组、没有从某个检查点恢复。重试期间上下文一条不能少——
半途丢掉的消息在真实场景里就是「模型忘了刚才做了什么」，然后重复一遍已经做过的事。

假端点用请求体 md5 验证了这一点：前三次 POST 的 body 完全一致。

## 工具报错是给模型的输入，不是给程序的信号

```java
try {
    output = registry.dispatch(name, args);
    failed = looksLikeError(output);
} catch (RuntimeException e) {
    output = "(error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
    failed = true;
}
if (failed) {
    toolErrors++;
    output = toolError(name, output);      // 包成 <tool-error> 再回灌
}
```

s10 的工具已经有 `(error: ...)` 字符串了，但循环里没人分辨它，
模型也看不出「这条是失败还是内容里恰好有 error 这个词」。包一层标签，两边都清楚了。

重点是那个 `catch`：工具抛异常是最容易把整个循环带走的地方——一个空指针就让整轮对话结束，
而模型根本没机会知道发生了什么。异常在这里不是控制流，是上下文。

## 死循环：判据只有一个

```java
String fp = name + "|" + canonical(args);
if (fp.equals(lastFp)) streak++; else { lastFp = fp; streak = 1; }
if (streak >= STOP_AT) return Decision.STOP;
if (streak == WARN_AT) return Decision.WARN;
```

`canonical` 会把字段名排序。模型每次生成的 JSON 字段顺序都可能不一样
（`{"a":1,"b":2}` 和 `{"b":2,"a":1}`），直接比字符串会把同一个调用判成两个，检测器基本失效。

不看工具结果做判据：结果里带时间戳、带行号、带随机 id，拿它比指纹反而会把「完全相同」判成「不一样」。

第 3 次注入提醒（一条 user 消息，不动 system，不毁 s10 攒的前缀），第 5 次中止。
中止时那一轮不再执行，但仍然给每条 `tool_call` 回一条 `(not executed)` ——
配对比执行重要，配对坏了下一次请求直接 400。

代价是会误伤：有些场景确实需要连续读同一个文件三次。所以阈值不能太低，
而且第一次是提醒不是中止——把判断权还给模型。

## 上下文超长：先承认估算错了

```java
if (e.failure() == Failure.CONTEXT_OVERFLOW && !overflowRetried) {
    overflowRetried = true;
    if (!compactor.forceCompact(messages)) throw e;
    continue;
}
```

`forceCompact` 绕过阈值直接压。s08 的 `maybeCompact` 是「自己估算，超了才压」，
估算和真实 token 数总有偏差；偏差大到被服务端拒了，还拿估算值判断要不要压，就是自欺欺人。

`overflowRetried` 只救一次：压完还超说明压缩没起作用，再试就是死循环。

## 实测

端点主动制造四次故障，加上剧本里的两个麻烦（工具报错、连续 5 次相同调用）：

```
   [fake] POST 1 → 429（Retry-After: 1）
   [fake] POST 2 → 503
   [fake] POST 3 → 200 但响应体半截（不是合法 JSON）
   [fake] 注入 400 context_length_exceeded
```

JVM 侧的退避与恢复：

```
   [retry] 第 1 次失败（HTTP 429 / TRANSIENT / Retry-After 1000ms），1000ms 后重发
   [retry] 第 2 次失败（HTTP 503 / TRANSIENT），938ms 后重发
   [retry] 第 3 次失败（HTTP 200 / TRANSIENT），1671ms 后重发
   [recover] 服务端拒收：上下文超长（HTTP 400），不等估算，直接压一次再发
   [loop] 连续 3 次相同调用，注入提醒
   [loop] 连续 5 次相同调用，中止这一轮任务
   [retry] 共重试 3 次，累计等待 3609ms，最长一次 1671ms
   [recover] 工具错误 5 次（已回灌给模型），死循环中止 1 次，循环提醒 1 次
```

注意第 3 行的 `HTTP 200 / TRANSIENT`：HTTP 是成功的，失败发生在解析。
这一条如果不单独处理，就会被当成「模型返回了空内容」，然后带着空消息继续往下跑。

十七项断言（正常 14 + 反例 3）：

```
retry_after_honored=YES          服务端说等 1s，实际 1.03s
backoff_grows=YES                0.58s → 1.48s
bad_json_retried=YES             半截 JSON 重发了一次
retry_sends_identical_body=YES   前三次 POST 请求体 md5 相同
tool_error_fed_back=YES          出现 <tool-error>，模型下一轮换了路径
tool_error_did_not_kill_loop=YES 工具报错后循环继续跑到第 10 轮
overflow_recovered=YES           400 超长 → 压缩 1 次 → 重发成功
loop_warning_injected=YES        第 3 次注入提醒
loop_warning_reached_model=YES   提醒进了下一轮请求
loop_stopped_at_five=YES         第 5 次不执行，直接收尾
not_executed_marked=YES          中止那次仍回了 tool 消息
tool_pairing_intact=YES          全部请求 tool_call 配对完整
retry_reported=YES               三次失败都记账
script_finished=YES              剧本 10 轮跑完
```

反例那遍（`SCENARIO=fatal`，端点一直返回 401）：

```
fatal_stopped_at_expected=YES    分类后只发 1 次（反例 RETRY_MODE=naive 时发满 4 次）
fatal_reported_as_fatal=YES      报的是 FATAL，不是「重试 4 次仍失败」
fatal_kept_reason=YES            401 与 invalid api key 原样保留
naive_reports_wrong_reason=YES   （naive 那一遍）最后一句话里没有真正的原因
```

## 跑一遍

```sh
# 正常场景
SCENARIO=main python3 fake-endpoint.py &
RETRY_MAX=4 RETRY_BASE_MS=500 CONTEXT_LIMIT=8000 \
  OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8911/v1 \
  MODEL_ID=fake mvn -q exec:java

# 401 场景（断言里验「不该重试的不重试」）
SCENARIO=fatal STDOUT_LOG=/tmp/s11_fatal.log python3 fake-endpoint.py &
OPENAI_API_KEY=sk-fake OPENAI_BASE_URL=http://127.0.0.1:8911/v1 \
  MODEL_ID=fake mvn -q exec:java
```

## 这一篇没解决的

- **重试会重复扣费**。超时的那一刻，服务端可能已经算完了，钱照付。真实产品要给请求带幂等键。
- **流式响应的恢复**。流断了以后从第几个 token 续，协议层不支持，只能整条重发。
- **循环检测的误伤**。连续读同一个文件三遍在有些任务里是合理的。这里靠「先提醒」把判断权还给模型，但不是根本解法。
- **没有熔断**。连续失败应该停止打同一个端点、切备用，这一篇只做到「这一条请求放弃」。
- **预算不可见**。重试花了多少钱、退避浪费了多少时间，只有日志，没有指标。

## 实验条件

| 项目 | 值 |
| --- | --- |
| 模型 | `deepseek-chat`（`MODEL_ID` 可换任意 OpenAI 兼容模型） |
| 重试上限 | `RETRY_MAX=4` |
| 退避基数 / 上限 | `RETRY_BASE_MS=500` / `RETRY_CAP_MS=8000` |
| 上下文预算 | `CONTEXT_LIMIT=8000`（触发阈值 0.75，测试里靠端点强制 400） |
| 故障注入 | 429(Retry-After 1) → 503 → 200 半截 JSON → 400 超长 |
| 剧本 | 10 轮：工具报错 1 次、改对 1 次、换工具 1 次、连续相同调用 5 次 |

退避的实测值每次都不一样（抖动就是干这个的），所以断言写的是「区间不重叠所以一定更大」，
不是「等于某个数」。
