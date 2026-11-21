package com.xalgocapital.claude.s08;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容协议的薄封装。
 *
 * 用 OpenAI Chat Completions 格式，而不是 Anthropic Messages 格式。
 * 好处：DeepSeek / 通义 / Kimi / GLM / 智谱等国内模型都提供兼容端点，
 * 换 base_url + apiKey + model 三个值就能切，读者容易复现实验。
 *
 * 只做一件事：把 messages + tools 发过去，把响应 JsonNode 拿回来。
 * 没有任何 Agent 逻辑——循环在 AgentLoop 里。
 */
public class OpenAICompatClient {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
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
     */
    public JsonNode createChatCompletion(ArrayNode messages, ArrayNode tools) {
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
            throw new RuntimeException("构造请求失败", e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("请求失败", e);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("API 返回 " + response.statusCode() + ": " + response.body());
        }

        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new RuntimeException("解析响应失败", e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
