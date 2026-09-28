# AgentStateExample 学习笔记（大白话版）

> 对应官网 building-blocks 的 Context & AgentState（状态部分）。
> 代码：`AgentStateExample.java` / `AgentStateExampleTest.java`（learning.agentscope.agent 包）。
> 案例一用"删目录实验"摸过这套机制，这里正面验证。

## 一句话总纲

**AgentState = 会话的"存档"（对话缓冲、权限、任务等运行时快照）；AgentStateStore = 存档盘。
换 store 实现，换的就是"重启不丢会话"的物理载体。**

## 1. 最重要的一条性质（UT 实证）

**跨 agent 实例恢复**：实例一说完名字就 `close()`（等价进程退出），实例二是全新的对象、
指到同一个状态目录——记忆照常接上，第二个实例的模型请求里带着第一个实例的对话。
这就是"服务重启不丢会话"的全部原理：状态在盘上，实例只是过客。

## 2. 产物布局的一个反直觉发现（实验实测）

| store 构造 | 落盘布局 |
|---|---|
| `new JsonFileAgentStateStore(customRoot)` | `root/<userId>/<sessionId>/agent_state.json`——**没有 agent 名层** |
| 默认（无参，`~/.agentscope/state`） | `~/.agentscope/state/<agentId>/<userId>/<sessionId>/agent_state.json` |

默认根目录那层 `<agentId>` 不是布局常量，而是因为**默认根是全机器多 agent 共享的**，
需要 agent 名防碰撞——这正好解释了案例一的坑："两个项目 agent 同名会串状态"。
想隔离，要么改 agent 名，要么给每个项目一个专属根目录（本案例的 `.agentscope/state-demo`）。

## 3. 常用操作速查

```java
new JsonFileAgentStateStore(path)   // JSON 文件 store（官网默认）
new InMemoryAgentStateStore()       // 内存 store（UT 用，进程结束即丢）
store.listSessionIds("alice")       // 枚举某用户名下的会话
store.exists(userId, sessionId)     // 会话存不存在
store.delete(userId, sessionId)     // 删会话
agent.clearContext(ctx)             // 清空会话上下文（下一轮从零开始，UT 实证）
agent.clearStateCache()             // 只清内存缓存，不动盘上数据
```

`clearContext` vs `clearStateCache`：前者"删档"，后者"丢掉内存里的副本下次重读"——
多副本部署下别的数据改了盘上状态时用它刷新本地视图。

## 4. 复现命令

```bash
mvn test -Dtest=AgentStateExampleTest

# 真实模型：实例一记名字 -> 关闭 -> 实例二回忆（需要 DASHSCOPE_API_KEY）
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.agent.AgentStateExample
# 产物：.agentscope/state-demo/alice/state-demo/agent_state.json
```
