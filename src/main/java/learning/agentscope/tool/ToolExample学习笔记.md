# ToolExample 学习笔记（大白话版）

> 对应官网 building-blocks/tool：
> https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html
>
> 代码：`ToolExample.java` / `ToolExampleTest.java`。

## 一句话总纲

`@Tool` 把普通 Java 方法变成模型可调用的工具，`@ToolParam` 描述模型需要提供的参数，
`Toolkit` 负责注册，`ReActAgent` 负责在推理循环里执行。

```text
模型返回 ToolUseBlock
  -> Toolkit 找到同名工具
  -> 反序列化参数并执行 Java 方法
  -> 生成 ToolResultBlock
  -> 把 TOOL 消息交给下一轮模型
  -> 模型输出最终回答
```

## 1. 注解式工具的三步

### 第一步：写普通方法

```java
@Tool(
        name = "add_numbers",
        description = "两个整数相加，返回和。",
        readOnly = true,
        concurrencySafe = true)
public int addNumbers(
        @ToolParam(name = "left", description = "左侧整数。") int left,
        @ToolParam(name = "right", description = "右侧整数。") int right) {
    return left + right;
}
```

- `name` 是模型调用时使用的工具名；
- `description` 会进入工具 Schema，决定模型什么时候选择它；
- `@ToolParam` 的 `name` 必须和模型输入 JSON 的字段对应；
- `readOnly` 和 `concurrencySafe` 是工具元数据，供运行时和权限系统使用。

### 第二步：注册到 Toolkit

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new CalculatorTools());
```

`registerTool(Object)` 会扫描对象上的 `@Tool` 方法，并生成给模型看的 JSON Schema。

### 第三步：交给 Agent

```java
ReActAgent agent = ReActAgent.builder()
        .model("dashscope:qwen-plus")
        .toolkit(toolkit)
        .build();
```

Agent 只会把注册过的工具暴露给模型；模型决定调用哪个工具，Java 代码不需要手动解析模型的工具调用 JSON。

## 2. 工具成功和工具失败

`add_numbers` 返回 `7` 时，框架会生成成功的 `ToolResultBlock`，并放进下一次模型请求的 `TOOL` 消息。

`divide_numbers` 遇到除数为零时抛出 `IllegalArgumentException`。框架会把异常转换成
`ToolResultState.ERROR` 的工具结果，模型仍能收到这条结果并决定如何回复；异常不会直接打断整个 Agent 调用。

## 3. 测试中的一个坑

fake model 返回 `ToolUseBlock` 时，必须同时提供参数 Map 和参数 JSON 字符串：

```java
new ToolUseBlock(
        "call-add",
        "add_numbers",
        Map.of("left", 3, "right", 4),
        "{\"left\":3,\"right\":4}",
        Map.of());
```

参数校验会读取 JSON 字符串；只填 Map 会导致工具参数校验失败。

## 4. 运行

```bash
mvn test -Dtest=ToolExampleTest

# 真实模型（需要 DASHSCOPE_API_KEY）
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.tool.ToolExample
```
