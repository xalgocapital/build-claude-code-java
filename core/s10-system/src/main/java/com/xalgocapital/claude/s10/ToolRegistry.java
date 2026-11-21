package com.xalgocapital.claude.s10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具分发表——s02 的全部新东西。
 *
 * 一个 Map：工具名 -> 处理函数。再加一个 tools 数组，把工具描述发给模型。
 *
 * 加一个工具 = 在这里 register 一次（一条 schema + 一个 handler）。
 * 循环代码一行都不用改——它只认 dispatch(name, args) 这个入口。
 *
 * 这就是「机制很多，循环一个」最漂亮的一处：
 * s01 的 while(true) 原封不动，能力边界靠往这张表里注册来扩展。
 */
public class ToolRegistry {
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final ArrayNode tools;
    private final ObjectMapper mapper;

    public ToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
        this.tools = mapper.createArrayNode();
    }

    /**
     * 注册一个工具：schema 发给模型（让它知道能调什么），handler 留在本地（真执行）。
     * 两件事绑在一次调用里，不会漏。
     */
    public void register(String name, String description, ObjectNode parameters, ToolHandler handler) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", name);
        fn.put("description", description);
        fn.set("parameters", parameters);
        entry.set("function", fn);
        tools.add(entry);
        handlers.put(name, handler);
    }

    /** 发给模型的 tools 数组（OpenAI 格式：tools[].function.parameters） */
    public ArrayNode schemas() {
        return tools;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * 查表执行。循环里原来那行 runBash(command) 换成了这个。
     * 未知工具不崩，返回一条错误让模型自己纠正——这是至少该有的最小健壮性。
     */
    public String dispatch(String name, JsonNode args) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            return "(error: unknown tool '" + name + "')";
        }
        return handler.execute(args);
    }
}
