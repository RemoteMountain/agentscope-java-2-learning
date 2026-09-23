# RuntimeContextExample 学习笔记（大白话版）

> 对应官网 building-blocks 的 Context & AgentState 一章。
> 代码：`RuntimeContextExample.java` / `RuntimeContextExampleTest.java`（learning.agentscope.agent 包）。
> 前面每个案例都在用它（userId/sessionId），这次把它本身拆开看。

## 一句话总纲

**RuntimeContext 是随每次 `call` 携带的"叫号单"：单子上的 `userId/sessionId` 决定加载哪份
AgentState（点哪份存档），单子背面还能写随请求走的杂项（属性）——工具不用模型转告，直接看单子。**

## 1. 叫号单上能写什么（三类）

```java
RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")                    // 内建：state 寻址键的一半
        .sessionId("ctx-demo")              // 内建：另一半
        .put("request_id", "req-1001")              // 字符串属性：杂项
        .put(TenantInfo.class, new TenantInfo(...)) // 类型化属性：强类型单例
        .build();
```

| 类别 | API | 典型用途 |
|---|---|---|
| 内建字段 | `getUserId()` / `getSessionId()` | 决定加载哪个 AgentState 槽位 |
| 字符串属性 | `put(String, Object)` / `get(String)` | request_id、trace_id 这类随请求走的标记 |
| 类型化属性 | `put(Class<T>, T)` / `get(Class<T>)` | 租户信息、权限快照这类结构化对象 |

还有 `getExtra()`（属性 Map 的可变视图）和 `RuntimeContext.empty()`（空单子）。

## 2. 工具怎么拿到单子：三条注入路径（UT 全部实测）

`@Tool` 方法的参数分两种命运：**带 `@ToolParam` 的由模型填**（出现在给模型看的 schema 里）；
**不带的由框架注入**（模型看不见、也伪造不了）。三种注入写法：

```java
@Tool(name = "echo_meta", ...)
public String echoMeta(
        @ToolParam(name = "content") String content,  // 模型填
        RuntimeContext context) {                      // 框架注入整张单子
    context.getUserId();
    context.get("request_id");          // 字符串属性
    context.get(TenantInfo.class);      // 类型化属性
}

@Tool(name = "echo_tenant", ...)
public String echoTenant(
        @ToolParam(name = "content") String content,
        TenantInfo tenant) { ... }       // 直接注入类型化属性本身！
```

第三种（POJO 直接当参数）是本案例实验确认的：**类型化属性会按类型直接注入到无注解参数**。
这是把"敏感信息"递给工具的正道——模型连参数都看不见，想伪造也没门（生产参考项目 的"拒绝模型自报
身份"用的就是这条思路）。

## 3. 属性的存活边界（实验实测，官网没细说）

| 场景 | 第一轮 put 的属性还能读吗 |
|---|---|
| 同一 agent、同一 `(userId, sessionId)`、新的 context 对象 | **能**——框架按会话槽位缓存了上下文 |
| 同一 agent、换 sessionId | **不能**——全新上下文，`request_id=null`、`tenant=无` |
| 换 agent 实例 / 重启进程 | **不能**——属性从不落盘（官网："not persisted"） |

所以准确的说法是：**属性活在"agent 实例 × 会话槽位"的内存里，既不跨会话、更不跨进程**。
要跨轮次稳定传递的信息，该放进 state 或工作区文件，别指望 context 属性。

## 4. 和前面知识的连线

- 叫号单上的 `(userId, sessionId)` = 案例 1/3 学的 state 寻址键；换了 sessionId 就换存档，
  所以属性也换了一套——边界实验顺带验证了槽位机制；
- `context.getAgentState()`：框架在 call 开始时把加载好的 AgentState 挂到单子上，中间件和
  工具都该从 `ctx.getAgentState()` 读（而不是 `agent.getAgentState()`——并发时后者给的是
  "最后活跃会话"的状态，官网原话警告）；
- 生产参考项目 往单子上挂 `compId/runId/capabilitySnapshotId/治理上下文`，就是字符串属性 +
  类型化属性的生产级用法。

## 5. 复现命令

```bash
mvn test -Dtest=RuntimeContextExampleTest

# 真实模型（需要 DASHSCOPE_API_KEY）：工具回显 alice / req-... / tenant-7
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.agent.RuntimeContextExample
```
