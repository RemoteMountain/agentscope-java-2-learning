# StreamingFirstAgent 学习笔记（大白话版）

> 对应官网"快速开始：流式查看推理与工具调用"。
> 代码：`StreamingFirstAgent.java` / `StreamingFirstAgentTest.java`（本包和 test 包下）。
> 接着 `FirstAgent学习笔记.md` 往下看，那篇讲"记忆存在哪"，这篇讲"回答怎么边生成边看"。

## 一句话总纲

**`call()` 是等快递：模型全部说完才给你一条完整回复。`streamEvents()` 是看直播：模型说到哪，事件流到哪。**

代码上只改一个方法名，返回值从 `Mono<Msg>`（一条最终消息）变成 `Flux<AgentEvent>`（一连串事件），消费方式照搬 Reactor：`doOnNext(...)` 逐个处理，`blockLast()` 等收尾。

## 1. 事件怎么接：两步固定套路

```java
agent.streamEvents(new UserMessage("..."), context)
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                System.out.print(((TextBlockDeltaEvent) event).getDelta());   // 文本增量，直接追加打印
            } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                System.out.println("[tool] " + ((ToolCallStartEvent) event).getToolCallName());
            }
        })
        .blockLast();
```

第一步 `getType()` 拿枚举判断类型，第二步强转成具体事件类取字段。官网示例用 if-else，本项目 `printEvent` 用了 switch，效果一样。

## 2. 事件家族速查（按用途分组）

反编译 `AgentEventType` 枚举拿到的全量 29 个，常用的就前四组：

| 分组 | 枚举值 | 拿什么字段 | 干嘛用 |
|---|---|---|---|
| 生命周期 | `AGENT_START` / `AGENT_END` / `AGENT_RESULT` | Result 事件 `getResult()` 拿最终 `Msg` | 知道整轮开始/结束，拿最终答案 |
| 文本 | `TEXT_BLOCK_START/DELTA/END` | `getDelta()` 增量片段 | 打字机效果：把 delta 一直追加就是完整回答 |
| 思考 | `THINKING_BLOCK_START/DELTA/END` | `getDelta()` | 推理模型的思考过程，同样增量 |
| 工具调用 | `TOOL_CALL_START/DELTA/END` | `getToolCallName()` / `getToolCallId()` | 模型决定调工具了 |
| 工具结果 | `TOOL_RESULT_START` / `TOOL_RESULT_TEXT_DELTA` / `TOOL_RESULT_DATA_DELTA` / `TOOL_RESULT_END` | `getToolCallName()` / `getState()` | 工具执行完、结果回来了 |
| 模型层 | `MODEL_CALL_START` / `MODEL_CALL_END` | — | 一次底层模型调用的边界 |
| 人机交互 | `REQUIRE_USER_CONFIRM` / `USER_CONFIRM_RESULT` / `REQUIRE_EXTERNAL_EXECUTION` / `EXTERNAL_EXECUTION_RESULT` | — | 权限确认、外部执行（后面章节） |
| 兜底 | `EXCEED_MAX_ITERS`（迭代超限）/ `REQUEST_STOP`（请求停止）/ `ALL_TOOLS_DENIED` / `HINT_BLOCK` / `CUSTOM` / `SUBAGENT_EXPOSED` / `DATA_BLOCK_*` | — | 异常和特殊场景 |

## 3. 带工具的一轮，事件顺序长这样（UT 验证过）

```
TOOL_CALL_START（模型说：我要调 get_current_time）
    ↓ 框架真的去执行工具
TOOL_RESULT_END（结果回来了，塞进上下文）
    ↓ 框架带着结果发起第二次模型调用
TEXT_BLOCK_DELTA × N（最终回答流式吐出）
AGENT_RESULT（完整答案，一条 Msg）
```

`StreamingFirstAgentTest` 断言了三件事，都是确定性证明：

1. **所有 `TEXT_BLOCK_DELTA` 拼起来 == 最终回答 == `AGENT_RESULT` 里的 Msg**——打字机效果不丢字；
2. **顺序**：工具调用开始 < 工具结果 < 第一个文本增量 < 最终结果——最终文本一定出现在工具结果之后；
3. **模型被调了两次**：第一次的输出是 `ToolUseBlock`（发起工具调用），第二次的请求里多了一条 `TOOL` 角色消息（工具结果），这就是智能体循环（ReAct）在流式下的样子。

## 4. fake model 怎么模拟"流式 + 工具调用"

这是本项目 UT 的核心技巧，以后每个案例都能复用：

- **模拟流式文本**：`Flux.just(resp1, resp2, ...)` 吐多个 `ChatResponse`，每个带一小段 `TextBlock`，最后一个标 `finishReason("stop")`；
- **模拟发起工具调用**：返回的 `ChatResponse` 内容放一个 `ToolUseBlock`，标 `finishReason("tool_calls")`——框架会真的执行工具，然后再来调你一次。
  **大坑**：`ToolUseBlock` 必须用**五参构造器**同时给 input Map 和 content JSON 字符串（如 `"{\"path\":\"hello.md\"}"`），框架做参数校验时读的是 content 里的 JSON；只给 input Map 会报“未找到所需属性 path”。详见 `UserWorkspaceDirTest`。

## 5. 顺手预习：自定义工具三步（完整版在"工具"案例）

```java
// 1. 方法上标 @Tool（name、description 会变成给模型看的说明书）
public String getCurrentTime() { ... }
// 2. 注册进 Toolkit
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ClockTool());
// 3. builder 传入
HarnessAgent.builder()...toolkit(toolkit).build();
```

带 `@ToolParam` 的参数由模型填，不标参数的（如 `RuntimeContext`）由框架注入。

## 6. 本轮踩的大坑：记忆钩子会在背后偷调模型

**现象**：`FirstAgentTest` 原本断言"2 轮对话 = 2 次模型调用"，某天突然变成 4 次，挂了。

**排查**（在 fake model 里打印每次收到的消息）：每轮对话结束后，Harness 的记忆钩子会**异步**追加一次模型调用，提示词一眼可辨：

- `"Extract NEW memories from this conversation window..."`——当天第一次：从这轮对话提炼新记忆；
- `"Today's daily ledger so far (your output will be appended after)..."`——当天后续轮次：往当日台账里追加。

**机制**（反编译 + 对照实验确认）：钩子是两个中间件——`MemoryFlushMiddleware`（每轮后提炼记忆，写入 `workspace/<userId>/memory/<当日>.md`）和 `MemoryMaintenanceMiddleware`（后台把每日记忆合并成 `MEMORY.md` 并清理过期文件，走 `MemoryConsolidator`）。`MemoryConfig` 可调：`flushTrigger.minGap`（节流间隔）、`consolidationMinGap`、甚至 `model(...)`——**可以给记忆流水线单独指定一个便宜模型**。

**坑点**：flush 是异步的，你的断言执行时它可能还没到（我们观测到同一测试忽而 4 次忽而 3 次，纯竞态）。

**修法**：UT 的构造入口统一 `.disableMemoryHooks()`。开关的确切区别，`MemoryHooksDifferenceTest` 做了对照实验：

| | 默认（钩子开） | `disableMemoryHooks()` |
|---|---|---|
| 每轮模型调用次数 | 回答 1 次 + 异步记忆 flush 1 次 | 恰好 1 次 |
| `workspace/<userId>/memory/` | 出现当日记忆文件 | 完全不产生 |
| 会话内记忆（state store 恢复） | 有 | 有（不受影响，另一套系统） |
| 跨会话长期记忆（MEMORY.md 注入） | 自动积累 | 不会再自动积累 |

注意它**只关自动流水线**：`memory_save`/`memory_search`/`memory_get` 工具还在（那是 `disableMemoryTools` 管的），转录、压缩、会话恢复也都不受影响。

## 7. 复现命令

```bash
# UT（不需要 API Key）
mvn test
mvn test -Dtest=StreamingFirstAgentTest

# 真实模型流式演示（需要 DASHSCOPE_API_KEY，终端看打字机效果和 [tool-call] 标签）
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.quickstart.StreamingFirstAgent
```

运行后看产物：转录文件 `.agentscope\workspace\alice\agents\note-taker\sessions\streaming-demo.jsonl` 里会有这轮的 `tool_use` / `tool_result` / 消息三类条目——上一案例说"转录只写不读"，这次的工具调用过程也会被完整记进档案。
