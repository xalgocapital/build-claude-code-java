package dev.lcc.s12;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个工具的处理函数：吃进参数 JSON，吐出结果字符串。
 *
 * s01 里工具执行是硬编码的一行 runBash(command)。
 * 把「执行」这件事抽成这个接口，工具才能进分发表。
 */
@FunctionalInterface
public interface ToolHandler {
    String execute(JsonNode args);
}
