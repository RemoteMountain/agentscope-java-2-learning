# ToolExecutionContextExample 学习笔记（大白话版）

> 对应官网 Tool 页的“接收 Context”：
> https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html#接收-context
>
> 代码：`ToolExecutionContextExample.java` / `ToolExecutionContextExampleTest.java`。

## 一句话总纲

`RuntimeContext` 是一次 `agent.call` 的上下文袋子。调用方把用户、租户、请求号等对象放进去，
工具执行时直接读取；这些对象不需要模型传入，也不会出现在工具 Schema 里。

```text
RuntimeContext.builder().put(UserContext.class, userContext).build()
  -> agent.call(messages, runtimeContext)
  -> Tool 收到同一个 RuntimeContext
  -> 工具读取当前用户/请求信息
```

## 1. 注解式 Tool：自动注入 POJO

```java
@Tool(name = "greet_user", description = "使用当前用户的姓名进行问候。")
public String greet(
        @ToolParam(name = "greeting", description = "问候语") String greeting,
        UserContext userContext) {
    return greeting + "，" + userContext.username() + "！";
}
```

带 `@ToolParam` 的参数来自模型 JSON；没有 `@ToolParam` 且是自定义 POJO 的参数，
由框架按类型从 `RuntimeContext` 注入。模型看到的 Schema 只有 `greeting`，看不到 `userContext`。

调用方按类型放入对象：

```java
RuntimeContext context = RuntimeContext.builder()
        .userId("alice")
        .sessionId("session-alice")
        .put(UserContext.class, new UserContext("alice", "zh", List.of("紧凑视图")))
        .build();

agent.call(List.of(new UserMessage("问候我")), context).block();
```

同一个 agent 可以服务多个用户；每次调用传入不同 `RuntimeContext`，工具读取到的就是对应用户的数据。

## 2. ToolBase：从 ToolCallParam 读取

需要自定义 Schema 或执行逻辑时，可以继承 `ToolBase`：

```java
@Override
public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
    RuntimeContext context = param.getRuntimeContext();
    String userId = context.getUserId();
    AuditContext audit = context.get(AuditContext.class);
    String note = String.valueOf(param.getInput().get("note"));
    // 组装 ToolResultBlock 返回
}
```

新代码使用 `param.getRuntimeContext()`。旧的 `ToolExecutionContext` 兼容 API 已标记弃用，
本案例不使用它。

## 3. 和 RuntimeContext 案例的边界

`RuntimeContextExample` 解释上下文的字段、属性和生命周期；本案例把焦点收窄到 Tool：

- 自动注入参数不会进入模型 Schema；
- `ToolBase` 如何拿到同一份上下文；
- 同一个 agent 的不同 call 如何读取不同用户的上下文。

## 4. 运行

```bash
mvn test -Dtest=ToolExecutionContextExampleTest

# 真实模型（需要 DASHSCOPE_API_KEY）
mvn -q compile exec:java \
  -Dexec.mainClass=learning.agentscope.tool.ToolExecutionContextExample
```
