# ReActAgentExample 学习笔记（大白话版）

> 对应官网"智能体基础"（building-blocks/agent）。
> 代码：`ReActAgentExample.java` / `ReActAgentExampleTest.java`（learning.agentscope.agent 包）。
> 前三个案例用的 HarnessAgent 是"整车"，这个案例把车拆了，只看发动机。

## 一句话总纲

**`ReActAgent` 是内核发动机，`HarnessAgent` 是"发动机 + 全套行李"的整车。**
拆掉行李（23 个默认工具、工作区、会话持久化、记忆钩子）之后，剩下的就是最纯粹的
"推理 → 行动 → 观察"循环。

## 1. 裸内核 vs HarnessAgent（UT 实测对比）

| | 裸 ReActAgent | HarnessAgent |
|---|---|---|
| 工具 | **只有自己注册的**（实测 `tools.size() == 1`） | 默认 23 个 |
| workspace | 没有 | 有（人格/知识/转录/记忆） |
| stateStore | 默认 null——**不持久化**，上下文只在内存里 | 默认 JSON 文件，跨进程恢复 |
| 记忆钩子 | 没有 | 默认开启（每轮后偷偷调模型） |

结论：要"轻、可控、自己搭一切"用 ReActAgent（生产参考项目 那种深度定制就走这条路再自己加中间件）；
要"开箱即用"用 HarnessAgent。

## 2. 三个方法的本质

- **`call(msg) → Mono<Msg>`**：跑完整的推理-行动循环，吐最终回复。带工具时就是
  思考 → 调工具 → 看结果 → 再思考……直到给出文字答复；
- **`observe(msg) → Mono<Void>`**：**只把消息塞进上下文，不吵醒模型**（实测：模型零调用）。
  用途：提前"喂事实"（下一轮 call 能看见）、多 agent 互相旁听输出；
- **`streamEvents(msg) → Flux<AgentEvent>`**：同 call，但直播全过程（和案例二同款）。

`observe` 的实验：先 `observe("用户的昵称是小鱼")`（模型 0 次调用），再 `call("我的昵称是什么？")`
——请求里带着"小鱼"，回答正确。**没有 stateStore 时上下文靠内存延续**（进程重启就没了，
要持久化得自己传 `.stateStore(...)`）。

## 3. maxIters 的真实语义（官网没写清，实验实测）

`maxIters` 默认 10。用一个"永远发起工具调用、永不回答"的死循环模型实测 `maxIters(2)`，
事件序列揭示了真实行为：

```
AGENT_START
迭代1: MODEL_CALL(带工具) → TOOL_CALL → TOOL_RESULT
迭代2: MODEL_CALL(带工具) → TOOL_CALL → TOOL_RESULT
EXCEED_MAX_ITERS          ← 上限触发
收尾:  MODEL_CALL(不带工具！) ← 没收工具，强制纯文本作答
AGENT_RESULT → AGENT_END   ← 仍然有最终结果
```

**不是硬中断报错，而是"没收工具、强制收尾"**：超限后再给模型一次不带任何工具的调用，
逼它用纯文本把话说完。所以模型总调用数 = maxIters + 1，且永远能拿到一个 AGENT_RESULT。
这个语义官网一笔带过，是本案例用实验补上的。

## 4. UT 技巧：ScriptedModel 脚本队列

fake model 带一个 `List<ChatResponse> script` 队列：按顺序消费脚本回复，耗尽后回落到默认
回复。比 if-else 计数器好读，多步循环场景直接"写剧本"。

## 5. 复现命令

```bash
mvn test -Dtest=ReActAgentExampleTest

# 真实模型（需要 DASHSCOPE_API_KEY）：
# call 查时间 -> observe 喂昵称 -> streamEvents 问昵称
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.agent.ReActAgentExample
```

## 6. 和生产参考项目的呼应

生产参考项目的 `DefaultRuntimeGenerationFactory` 构建的每个 HarnessAgent 都 `.disable...` 了一大排
默认能力——本质上就是"往裸内核方向拆"。学完本案例再看他们那 50 行 builder，会发现
**ReActAgent + 自选中间件**和**HarnessAgent + 全 disable**是同一件事的两种写法。
