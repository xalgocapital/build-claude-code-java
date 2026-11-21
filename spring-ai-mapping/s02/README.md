# s02 对照 · Spring AI 的工具定义

同一个「工具分发表」，Spring AI 2.0 里怎么写。对照 `core/s02-tools` 的手写 `ToolRegistry`。

## 解决什么问题

手写版要自己拼 schema、自己维护 `Map<String, ToolHandler>`、自己 dispatch。Spring AI 用 `@Tool` 注解一次搞定这三件事。这一对照回答：框架把「注册一个工具」简化成了什么，`@Tool` / `@ToolParam` 各自对应手写版的哪一步。

## 实验条件

| 项 | 值 |
|---|---|
| 模型 | deepseek-chat（可换任意 OpenAI 兼容端点） |
| temperature | 服务端默认，未在代码里指定 |
| 固定提示词 | 见 `SpringAiToolsApp.java` 里的 user prompt |
| 工具数 | 5（bash / read_file / write_file / edit_file / glob） |
| 说明 | 未接真实 API 时不产生工具调用，仅演示 API 链路；真实输出随模型版本漂移，固定以上条件才可对照片 |

## 手写版做了什么

```
ToolRegistry.register(name, description, parameters, handler)  // 注册一次
  -> schema 进 tools 数组发给模型
  -> handler 进 Map，dispatch(name) 查表执行
加一个工具 = register 一次，schema 和实现绑一起
```

## 这里怎么写

一个 `@Tool` 方法 = 手写版的一次 register：

```java
@Tool(description = "Read a UTF-8 text file")
public String readFile(
        @ToolParam(description = "File path") String path,
        @ToolParam(description = "Max lines", required = false) Integer limit) { ... }
```

框架做了手写版亲手写的所有事：

| 手写版 | Spring AI 2.0 |
|---|---|
| register 里拼 parameters schema | `@Tool` / `@ToolParam` 反射生成 schema |
| `Map<String, ToolHandler>` 查表 | 框架扫描 `@Tool` 方法建分发表 |
| dispatch(name, args) 调用 | ToolCallingManager 解析参数并调用 |
| 结果回传 messages | advisor 内部维护 |

`.tools(new BuiltinTools())` 一次注册 5 个工具，等价于手写版 `BuiltinTools.install(registry)`。

## 关键差异

手写版你能看见那张 Map、看见查表、看见 `dispatch` 怎么按名字找到函数。这里那些全藏在框架里。生产里应当用框架，但没手写过一遍，你不会知道 `@Tool` 背后发生了什么——这正是 `core/` 存在的意义。

## 跑法

```sh
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com   # 不带 /v1，Spring AI 自己拼
export MODEL_ID=deepseek-chat
mvn -q spring-boot:run
```

> 版本：Spring AI 2.0.0，基线 Spring Boot 4.0 + Java 21。`@Tool` 包名 `org.springframework.ai.tool.annotation.Tool`。
