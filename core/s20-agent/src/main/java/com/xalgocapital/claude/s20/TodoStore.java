package com.xalgocapital.claude.s20;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * s05 的全部新东西：一个带状态的任务列表。
 *
 * todo_write 不读文件、不跑命令——它不给 Agent 增加任何「执行能力」，
 * 只给它一层「显式计划」。列表活在进程内存里，退出即清空（真实 CC 的 V1
 * 也是这样；文件持久化的 V2 是 s12 的事）。
 *
 * 每个 todo 三态：pending（待办）/ in_progress（在做）/ completed（做完）。
 * 每次调用提交「完整列表」覆盖旧的——不是增量打补丁，模型必须自己记住全貌。
 */
public class TodoStore {
    public record Todo(String content, String status) {}

    private final ObjectMapper mapper;
    private List<Todo> todos = new ArrayList<>();

    public TodoStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 工具描述——强调「先列计划」，让模型第一轮就想到调它 */
    public static final String DESCRIPTION =
            "Create and manage a structured task list for complex work. "
            + "Call this FIRST to lay out a plan, then mark each item in_progress / completed as you go. "
            + "Each call replaces the entire list. It does not read or modify files by itself.";

    /** todo_write 的 schema：嵌套结构，todos 是数组，每项 content + status(enum) */
    public ObjectNode schema() {
        ObjectNode props = mapper.createObjectNode();

        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        ObjectNode itemProps = mapper.createObjectNode();

        ObjectNode content = mapper.createObjectNode();
        content.put("type", "string");
        content.put("description", "What needs to be done");
        itemProps.set("content", content);

        ObjectNode status = mapper.createObjectNode();
        status.put("type", "string");
        ArrayNode states = mapper.createArrayNode();
        states.add("pending").add("in_progress").add("completed");
        status.set("enum", states);
        status.put("description", "Current state of this task");
        itemProps.set("status", status);

        item.set("properties", itemProps);
        item.set("required", mapper.createArrayNode().add("content").add("status"));

        ObjectNode todosArr = mapper.createObjectNode();
        todosArr.put("type", "array");
        todosArr.set("items", item);
        todosArr.put("description", "The full task list; replaces the previous list");
        props.set("todos", todosArr);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("todos"));
        return schema;
    }

    /** 执行：解析完整列表 → 存内存 → 终端打印看板 → 返回摘要给模型 */
    public String update(JsonNode args) {
        JsonNode arr = args.path("todos");
        List<Todo> next = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String content = t.path("content").asText("").strip();
                String status = t.path("status").asText("pending");
                if (!content.isEmpty()) {
                    next.add(new Todo(content, status));
                }
            }
        }
        this.todos = next;
        printBoard();
        return summarize();
    }

    /** 终端看板：三态用不同符号，底部一行进度 */
    private void printBoard() {
        System.out.println("\n   === TODO BOARD ===");
        for (String line : board().split("\n")) {
            System.out.println("   " + line);
        }
    }

    /**
     * 看板文本。s08 的压缩用它把清单重新贴回上下文——
     * 压缩只留摘要，而清单是每轮都在变的东西，不能指望摘要记得住。
     */
    public String board() {
        StringBuilder sb = new StringBuilder();
        for (Todo t : todos) {
            String icon = switch (t.status()) {
                case "in_progress" -> "▸";
                case "completed" -> "✓";
                default -> " ";
            };
            sb.append('[').append(icon).append("] ").append(t.content()).append('\n');
        }
        sb.append("Progress: ").append(count("completed")).append('/').append(todos.size()).append(" complete");
        return sb.toString();
    }

    private String summarize() {
        return "Updated " + todos.size() + " tasks (" + count("completed") + " done, "
                + count("in_progress") + " in progress, " + count("pending") + " pending)";
    }

    private int count(String status) {
        return (int) todos.stream().filter(t -> status.equals(t.status())).count();
    }
}
