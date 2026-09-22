# AgentScope Java 2 学习工程

这是一个可以直接运行的 AgentScope Java 2.0 案例库。每个案例都尽量使用官网中的类名、方法名和配置方式，并配有注释、UT 和路线记录，便于边看文档边运行代码。

当前版本固定为 `2.0.3`，JDK 使用 17+，本机验证环境为 JDK 21 和 Maven 3.9.16。

## 案例一：快速上手 / 第一个智能体

代码位置：

- `src/main/java/learning/agentscope/quickstart/FirstAgent.java`
- `src/test/java/learning/agentscope/quickstart/FirstAgentTest.java`
- `src/test/java/learning/agentscope/quickstart/WorkspaceTranscriptOnlyTest.java`（验证转录不会自动恢复进对话）
- `src/main/java/learning/agentscope/quickstart/FirstAgent学习笔记.md`（大白话总结：两个目录的分工、三个删目录实验、坑位清单）
- `.agentscope/workspace/AGENTS.md`

它对应官网快速开始中的“第一个智能体”，演示三件事：

1. `HarnessAgent` 通过工作区中的 `AGENTS.md` 加载人格。
2. `RuntimeContext` 通过相同的 `userId` 和 `sessionId` 恢复上一轮会话。
3. `CompactionConfig` 配置对话压缩阈值；本案例的两轮对话不会触发压缩，但 builder 配置与官网保持一致。

官网来源：

- 快速开始：https://java.agentscope.io/v2/zh/docs/quickstart.html
- 智能体：https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html
- Harness 架构：https://java.agentscope.io/v2/zh/docs/harness/architecture.html
- 工作区：https://java.agentscope.io/v2/zh/docs/harness/workspace.html
- Maven Central 版本：https://repo1.maven.org/maven2/io/agentscope/agentscope-harness/maven-metadata.xml

## 案例二：快速上手 / 流式查看推理与工具调用

代码位置：

- `src/main/java/learning/agentscope/quickstart/StreamingFirstAgent.java`
- `src/test/java/learning/agentscope/quickstart/StreamingFirstAgentTest.java`
- `src/main/java/learning/agentscope/quickstart/StreamingFirstAgent学习笔记.md`

它对应官网快速开始中的“流式查看推理与工具调用”，演示三件事：

1. 把 `call(...)` 换成 `streamEvents(...)`，拿到 `Flux<AgentEvent>` 事件流而不是一条最终回复。
2. 用 `event.getType()` 分发事件：`TEXT_BLOCK_DELTA` 文本增量、`TOOL_CALL_START` 工具调用开始、`AGENT_RESULT` 最终结果等。
3. 注册一个最小的 `@Tool` 自定义工具（`ClockTool`），让真实运行能看到工具调用事件；UT 用 fake model 的 `ToolUseBlock` 确定性复现“调用工具 -> 拿到结果 -> 流式作答”的完整循环。

官网来源：

- 快速开始（流式小节）：https://java.agentscope.io/v2/zh/docs/quickstart.html
- 工具（`@Tool` / `@ToolParam` 的完整用法在后续案例展开）：https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html

## 案例三：快速上手 / 多用户并发

代码位置：

- `src/main/java/learning/agentscope/quickstart/MultiUserFirstAgent.java`
- `src/test/java/learning/agentscope/quickstart/MultiUserFirstAgentTest.java`
- `src/main/java/learning/agentscope/quickstart/MultiUserFirstAgent学习笔记.md`

它对应官网快速开始中的“多用户并发”，演示三件事：

1. agent 实例在调用之间无状态：应用启动时 build 一次（单例），全程复用，身份随每次 `call` 通过 `RuntimeContext` 携带。
2. 状态按 `(userId, sessionId)` 隔离：alice 换一个 sessionId 就不记得旧会话；bob 的上下文不会混入 alice 的对话；产物按用户分目录落盘。
3. 并发安全由框架保证：同一 `(userId, sessionId)` 的请求被 `SessionTurnGate` 自动串行化（UT 实测 inFlight 恒为 1），不同会话完全并行（UT 实测 inFlight 达到 2），开发者无需加锁。

官网来源：

- 快速开始（多用户并发小节）：https://java.agentscope.io/v2/zh/docs/quickstart.html
- 上线指南（生产用 Redis 状态存储等）：https://java.agentscope.io/v2/zh/docs/others/going-to-production.html

## 运行

### 运行 UT

UT 使用内存状态存储和假的 `Model`，不需要 API Key，也不会访问网络：

```bash
mvn test
```

### 运行官网 DashScope 示例

先设置环境变量 `DASHSCOPE_API_KEY`，然后从项目根目录执行：

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=learning.agentscope.quickstart.FirstAgent
```

Windows PowerShell 示例：

```powershell
$env:DASHSCOPE_API_KEY = "你的 API Key"
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.quickstart.FirstAgent
```

### 运行流式案例

同样需要 `DASHSCOPE_API_KEY`：

```bash
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.quickstart.StreamingFirstAgent
```

运行时终端会逐段打印文本增量、`[tool-call]` 工具调用标签和最终回答。

### 运行多用户并发案例

同样需要 `DASHSCOPE_API_KEY`：

```bash
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.quickstart.MultiUserFirstAgent
```

运行后可在 `.agentscope/workspace/` 下看到 `alice/` 与 `bob/` 两个用户各自独立的产物目录。

运行第二次时，如果仍使用相同的 `userId`、`sessionId` 和 agent 名称，AgentScope 会从默认状态目录恢复会话。默认状态目录在用户目录下的 `.agentscope/state/`，与工作区分开；这是官网明确说明的设计。

## 工程约定

- `src/main/java`：可直接运行的官网案例。
- `src/test/java`：不依赖真实模型的确定性测试；外部模型只在 `main` 中演示。
- `.agentscope/workspace`：案例使用的工作区种子文件；运行时产物已加入 `.gitignore`。
- `ROADMAP.md`：全站案例路线、当前进度和下一步入口。

暂不在本次切片实现流式事件、多用户并发运行、工具、MCP、记忆、子 agent、沙箱等后续案例，详见 `ROADMAP.md`。
