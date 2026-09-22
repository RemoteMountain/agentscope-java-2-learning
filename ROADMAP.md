# AgentScope Java 2 学习路线

## 使用方式

每次新增案例都应同时更新本文件：

- 把案例标记为 `已完成`、`进行中` 或 `待实现`。
- 写清楚对应官网 URL、代码入口和验证命令。
- 下一次继续时，从第一个 `待实现` 项开始，不重复改动已完成案例。

## 当前进度

当前已完成：**快速上手：第一个智能体**、**快速上手：流式查看**、**快速上手：多用户并发**。

下一次默认从：**智能体基础（building-blocks/agent）** 开始。

## 路线总览

| 状态 | 模块 | 学习目标 | 计划入口 |
|---|---|---|---|
| 已完成 | 快速上手：第一个智能体 | `HarnessAgent`、工作区人格、`RuntimeContext`、会话状态恢复、压缩配置 | `FirstAgent.java` |
| 已完成 | 快速上手：流式查看 | `streamEvents`、文本增量事件、工具调用开始事件 | `StreamingFirstAgent.java` |
| 已完成 | 快速上手：多用户并发 | 单例 agent、不同 `(userId, sessionId)` 的状态隔离与并发 | `MultiUserFirstAgent.java` |
| 待实现 | 智能体基础 | `ReActAgent`、`call`、`observe`、`streamEvents`、最大迭代次数 | `building-blocks/agent` |
| 待实现 | RuntimeContext | 字符串属性、类型化属性、工具上下文注入 | `RuntimeContextExample.java` |
| 待实现 | 状态与会话 | `AgentState`、`AgentStateStore`、JSON 文件存储、内存存储 | `AgentStateExample.java` |
| 待实现 | 工具 | `@Tool`、`Toolkit`、工具参数、工具结果和错误 | `ToolExample.java` |
| 待实现 | 工具执行上下文 | 工具读取 `RuntimeContext` | `ToolExecutionContextExample.java` |
| 待实现 | MCP | MCP client、HTTP/stdio server、工具注册 | `McpExample.java` |
| 待实现 | 权限系统 | allow/deny/ask、工具确认和规则持久化 | `PermissionExample.java` |
| 待实现 | 人机交互 | `RequireUserConfirmEvent`、确认结果、恢复调用 | `PermissionHITLExample.java` |
| 待实现 | 外部工具执行 | `RequireExternalExecutionEvent`、外部结果回传 | `ExternalExecutionExample.java` |
| 待实现 | 结构化输出 | Java class schema、`JsonNode` schema、强类型结果读取 | `StructuredOutputExample.java` |
| 待实现 | 工作区 | `AGENTS.md`、`MEMORY.md`、knowledge、skills、subagents、`tools.json` | `WorkspaceExample.java` |
| 待实现 | 长期记忆 | `MEMORY.md`、每日 memory、memory tools、记忆合并 | `MemoryExample.java` |
| 待实现 | 上下文压缩 | 消息阈值、token 阈值、保留尾部、压缩前 flush/offload | `CompactionExample.java` |
| 待实现 | 大工具结果卸载 | `ToolResultEvictionConfig` 和 `read_file` 指针 | `ToolResultEvictionExample.java` |
| 待实现 | 技能 | workspace/classpath/Git 等 skill repository、动态加载 | `SkillExample.java` |
| 待实现 | 自学习技能 | `propose_skill`、审批 gate、curator | `SkillCurationExample.java` |
| 待实现 | 子 agent | 同步委派、后台任务、结果通知和任务存储 | `SubagentExample.java` |
| 待实现 | Plan Mode | `plan_enter`、`plan_write`、只读阶段、恢复执行 | `PlanModeExample.java` |
| 待实现 | 文件系统 | local、remote、sandbox filesystem 和路径策略 | `FilesystemExample.java` |
| 待实现 | 沙箱 | Docker/K8s/AgentRun、快照、恢复和执行隔离 | `SandboxExample.java` |
| 待实现 | Channel | session 管理、事件路由、SSE/流式输出、多 agent 路由 | `ChannelExample.java` |
| 待实现 | 生产部署 | Redis/MySQL 状态存储、分布式 filesystem、多副本恢复 | `ProductionExample.java` |

## 本轮案例的边界

已完成的 `FirstAgent` 只验证“第一个智能体”这条最小链路。虽然它配置了官网展示的压缩 builder，但没有为了制造大量对话而额外触发压缩；压缩行为会在对应路线项中单独用确定性方式验证。

流式事件、多用户并发和真实模型错误处理属于同一快速开始页面的后续小节，按表格顺序继续实现。

`StreamingFirstAgent` 已覆盖流式事件；它顺带注册了一个 `@Tool` 最小自定义工具（工具章节的预习，完整工具用法仍在“工具”路线项展开）。本轮发现并绕开的坑：Harness 的记忆钩子（`MemoryFlushMiddleware` 等）会在每轮结束后异步追加一次模型调用（提示词以 "Extract NEW memories..."/"Today's daily ledger..." 开头），与测试断言存在竞态；UT 的构造入口统一 `.disableMemoryHooks()`。开关的确切区别由 `MemoryHooksDifferenceTest` 对照验证，长期记忆行为留给“长期记忆”路线项。

`MultiUserFirstAgent` 已覆盖单例多用户与并发语义（隔离、跨会话并行、同会话串行，均由 UT 用 inFlight 探针确定性验证）；默认 `JsonFileAgentStateStore` 的单机限制与生产 Redis 方案留给“生产部署”路线项。
