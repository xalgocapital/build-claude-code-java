package dev.lcc.s13;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * OpenAI 兼容协议的薄封装。
 *
 * s12 相对 s10 只加了一件事：失败之后怎么办。
 * 但这件事不能加在循环里——循环不知道 HTTP 状态码，也不该知道。
 * 所以「分类 + 退避 + 重试」全在这个类内部完成，对 AgentLoop 而言，
 * createChatCompletion 的语义从「发一次」变成了「发到成功、或者不该再发为止」。
 *
 * 唯一的例外是 CONTEXT_OVERFLOW：它不该在这里被重试（再发一次还是一样长），
 * 抛给循环，由循环先压缩再重发。谁有能力处理就交给谁。
 */
public class OpenAICompatClient {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RetryPolicy retry = new RetryPolicy();
    /** 反例开关：把所有失败都当临时故障，不看分类 */
    private static final boolean NAIVE = "naive".equals(System.getenv().getOrDefault("RETRY_MODE", "sorted"));
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * @param baseUrl 兼容端点根地址，如 https://api.deepseek.com/v1 （注意带 /v1）
     */
    public OpenAICompatClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 发一轮请求。messages 是完整对话数组，tools 是 OpenAI 格式的工具定义数组。
     *
     * 一个细节：重试用的是**同一个 messages 引用**，不是重建。
     * 重试期间上下文一条不能少——半途丢掉的消息在真实场景里就是「模型忘了刚才做了什么」。
     */
    public JsonNode createChatCompletion(ArrayNode messages, ArrayNode tools) {
        ApiException last = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                return once(messages, tools);
            } catch (ApiException e) {
                last = e;
                if (!NAIVE && e.failure() != Failure.TRANSIENT) {
                    throw e;    // FATAL 交给调用方收尾，OVERFLOW 交给循环去压缩
                }
                if (attempt == retry.maxAttempts()) {
                    break;
                }
                long wait = retry.delayFor(attempt, e.retryAfterMs());
                System.out.println("   [retry] 第 " + attempt + " 次失败（" + brief(e) + "），"
                        + wait + "ms 后重发");
                retry.sleep(wait);
            }
        }
        throw new ApiException(Failure.TRANSIENT, last == null ? 0 : last.status(), 0,
                "重试 " + retry.maxAttempts() + " 次仍失败：" + (last == null ? "?" : brief(last)), last);
    }

    /** 发一次，不做任何重试。失败统一转成 ApiException */
    private JsonNode once(ArrayNode messages, ArrayNode tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8000);
        body.set("messages", messages);
        body.set("tools", tools);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new ApiException(Failure.FATAL, 0, 0, "构造请求失败：" + e.getMessage(), e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // 超时：请求可能已经在服务端算了一半，账单照付，但重发是唯一能做的事
            throw new ApiException(Failure.TRANSIENT, 0, 0, "请求超时", e);
        } catch (IOException e) {
            throw new ApiException(Failure.TRANSIENT, 0, 0, "连接失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(Failure.FATAL, 0, 0, "线程被中断", e);
        }

        if (response.statusCode() != 200) {
            Failure kind = Failure.of(response.statusCode(), response.body());
            long retryAfter = RetryPolicy.retryAfterMillis(
                    response.headers().firstValue("retry-after").orElse(null));
            throw new ApiException(kind, response.statusCode(), retryAfter,
                    "HTTP " + response.statusCode() + "：" + clip(response.body()), null);
        }

        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            // 响应体半截 / 被截断：按临时故障重试。这一条在流式接口和中间代理上很常见
            throw new ApiException(Failure.TRANSIENT, 200, 0,
                    "响应不是合法 JSON（" + response.body().length() + " 字符）：" + clip(response.body()), e);
        }
    }

    private static String brief(ApiException e) {
        return "HTTP " + e.status() + " / " + e.failure()
                + (e.retryAfterMs() > 0 ? " / Retry-After " + e.retryAfterMs() + "ms" : "");
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public RetryPolicy retry() {
        return retry;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
