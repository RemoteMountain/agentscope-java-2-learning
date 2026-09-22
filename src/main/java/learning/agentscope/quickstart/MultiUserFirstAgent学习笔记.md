# MultiUserFirstAgent 学习笔记（大白话版）

> 对应官网"快速开始：多用户并发"。
> 代码：`MultiUserFirstAgent.java` / `MultiUserFirstAgentTest.java`。
> 前两篇讲"记忆存哪"和"回答怎么看"，这篇讲"一个 agent 怎么同时服务很多人"。

## 一句话总纲

**agent 实例是个无状态的"柜台窗口"，用户身份不在窗口上，而在每次递进来的叫号单（RuntimeContext）上。**
所以一个窗口（单例）就能服务所有人——换人不用换窗口，换叫号单就行。

## 1. 三个核心事实（全部有 UT 实证）

| 事实 | 大白话 | 怎么证明的 |
|---|---|---|
| 单例复用 | builder 一个字不用改，build 一次全程用，别按用户建实例 | 整个测试类从头到尾只用一个 agent 实例跑完所有断言 |
| 按 `(userId, sessionId)` 隔离 | alice 换个 sessionId 就"失忆"；bob 的上下文里一个字都没有 alice 的 | 断言 bob 的第 5 次请求不含"天宇"；alice 新会话得到"没有足够信息" |
| 同会话串行、跨会话并行 | 同一个人的同一场对话排队（防止并发写坏状态）；不同人/不同场同时进行 | inFlight 探针：同会话恒为 1，跨会话达到 2 |

## 2. 串行/并行是谁管的：SessionTurnGate

反编译确认：`HarnessGateway` 里有个 `SessionTurnGate`，每次回合开始前要 `acquire(sessionKey)`
拿一个 `TurnLease`（租约）——**锁的粒度是 `(userId, sessionId)` 这个字符串键**：

- 同键请求排队等租约 → 串行化，所以第二轮请求稳稳带上第一轮的历史（UT 断言过），不怕并发写坏 state；
- 不同键各拿各的租约 → 完全并行，谁也不等谁。

开发者一行锁代码都不用写。

## 3. UT 怎么"看见"并发：inFlight 探针

fake model 里放个计数器，进入 `stream()` 时 +1、记录最大值、sleep 300ms（模拟模型延迟）、退出时 -1：

- **跨会话测试**：两路调用 `Mono.zip` + `subscribeOn(Schedulers.boundedElastic())` 同时发起——
  如果框架真并行，两个 300ms 窗口必然重叠，`maxInFlight == 2`；如果假装并行实际排队，只会是 1；
- **同会话测试**：同样并发发起——被 TurnGate 排队的话，第二个 `stream()` 要等第一个退出才进，
  `maxInFlight == 1`。

`subscribeOn(boundedElastic)` 是在模拟"两个 HTTP 请求线程"；官网示例的形态就是 web handler 里
每次请求 `.block()`，并发由 web 容器天然提供，CLI 里我们用 zip 手动模拟。

## 4. 产物也按人分家

跑完 main 看 `.agentscope/workspace/`：`alice/` 和 `bob/` 各有一套 `agents/note-taker/sessions/`
转录、`default/.../events/` 事件流——对应第一篇笔记的结论：NamespaceFactory 默认命名空间就是
`[userId]`，**一切按用户落盘，物理上就分开了**。

## 5. 坑位与边界

1. **默认 `JsonFileAgentStateStore` 只适合单机开发**（官网原话级别的要求）：多副本部署时状态在各机器
   本地文件里，会话恢复就乱了——生产要换 `RedisAgentStateStore` 或自实现，留给"生产部署"案例。
2. 老坑复发预警：**state 目录按 agent 名全局共享**（`~/.agentscope/state/note-taker/`）。多用户案例里
   同一台机器上另一个项目也建了叫 `note-taker` 的 agent，两边的会话状态会互相串——要么改 agent 名，
   要么显式传 `.stateStore(...)`。
3. 并发断言别忘了数清调用序号：第一次踩坑就是断言用了下标 3（其实是 alice 自己的回忆轮），
   以为是串台，其实是数错了。

## 6. 复现命令

```bash
# UT（不需要 API Key）
mvn test -Dtest=MultiUserFirstAgentTest

# 真实模型：两个用户同时提问 + 各自回忆（需要 DASHSCOPE_API_KEY）
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.quickstart.MultiUserFirstAgent
```
