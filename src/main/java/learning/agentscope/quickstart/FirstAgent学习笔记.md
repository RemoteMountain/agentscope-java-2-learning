# FirstAgent 学习笔记（大白话版）

> 对应官网"快速开始：第一个智能体"。
> 代码：`FirstAgent.java` / `FirstAgentTest.java` / `WorkspaceTranscriptOnlyTest.java`（都在本包和对应 test 包下）。
> 这份笔记不是照抄官网，而是**跑完案例 + 做了三次删目录实验之后**真正搞明白的事。看不懂官网时回来看这份。

## 一句话总纲

**模型是金鱼，没有记性**。每次回答，它只看得见这一次递给它的输入。

AgentScope 框架干的全部活，就是围着这条金鱼当好两个角色：

1. **秘书**：替金鱼保管聊天记录，每轮开答前把完整记录重新递给它（靠 `state`，存在**用户目录**）；
2. **后勤**：给金鱼备好工位——岗位说明书、笔记本、档案柜（靠 `workspace`，存在**项目目录**）。

state 回答“这场会怎么接着聊”，workspace 回答“过去发生过的一切，如何可查、可看、可沉淀”。
模型只有一块小黑板（上下文窗口），框架靠“小工作集（state）+ 大档案库（workspace）”
拼出无限记忆的效果——档案库平时安静躺着，但压缩时兜底、检索时供货、你想复盘时随叫随到。

## 1. 两个目录，各管一摊（最重要的一张表）

用打游戏类比：**游戏装在游戏目录，存档放在"我的文档"**，两边分开放。

|            | workspace（项目里） | state（用户目录） |
|------------|---|---|
| 实际位置   | `F:\study\AgentScope2.0\.agentscope\workspace\` | `C:\Users\你\.agentscope\state\note-taker\` |
| 里面装什么 | 人格说明书 `AGENTS.md`、对话转录 `sessions/`、记忆笔记 `memory/`（以后还有知识、技能） | 只有一个 `agent_state.json`：当前会话聊到哪了（`context` 数组） |
| 类比       | 工位：说明书 + 笔记本 + 档案柜 | 白板：这场会正在进行的内容 |
| 谁来写     | `AGENTS.md` 是**你手写的**；转录和记忆是框架写的 | 框架全自动 |
| 删了会怎样 | 丢人格、丢档案；但对话照样能恢复（从存档） | 这场会失忆，从零开始（档案柜还在，能翻） |
| 可不可以换 | 路径随便指定，跟着项目走 | 实现可换：测试用内存，生产用 Redis |
| **含义**   | 这个 agent 是谁、知道什么、沉淀了什么 | 跨调用、跨进程怎么恢复这次会话|
| **定位**   | workspace 实际上是 agent 文件系统操作的根边界 | 本地目录还是沙箱由配置决定|

| 东西 | 类比 | 特点 |
| --- | --- | --- |
| state 的 context | 白板 | 只放当前这场会，容量有限（模型上下文窗口），会擦（压缩、删存档） |
| workspace 的 sessions 转录 | 档案柜里的全量会议纪要 | 无损、永不压缩、跨所有会话 |
| workspace 的 memory 笔记 | 从历次会议提炼的工作手册 | 精华版，每次开会前发到手里 |

| 东西 | 会不会压缩 |
| --- | --- |
| agents/.../sessions/*.jsonl 消息转录 | 永不压缩（官网原话），全量兜底，压缩敢下手就是因为档案还在 |
| default/.../events/*.jsonl 事件段 | 有 compact() 内部整理机制（字节码可见），是段文件合并，不是对话压缩；细节留到“上下文压缩”案例验证 |
| memory/*.md | 不压缩，是“追加 + 后台提炼进 MEMORY.md" |
| 特别大的工具结果 | 可配置“卸载”（ToolResultEvictionConfig，路线图后续案例） |
真正会被压缩的只有一个地方：state 里的对话缓冲（~/.agentscope/state/.../agent_state.json 的 context 数组）。就是 CompactionConfig(30, 10) 管的事：消息攒到 30 条，压成摘要、只留最近 10 条原文——因为模型的上下文窗口装不下无限对话。

目录里实际长这样：

```
F:\study\AgentScope2.0\.agentscope\workspace\
├─ AGENTS.md                        ← 手写的人格说明书（种子文件！框架不会生成）
└─ alice\                           ← 运行时产物（按 userId 分目录）
   ├─ agents\note-taker\sessions\demo-session.jsonl   ← 对话转录（档案柜）
   ├─ default\note-taker\demo-session\events\*.jsonl  ← 事件记录
   └─ memory\2026-09-21.md          ← agent 自己写的记忆笔记

C:\Users\你\.agentscope\state\note-taker\alice\demo-session\
└─ agent_state.json                 ← 存档：全部消息 + 权限、工具等运行时状态
```

## 2. 第二轮为什么记得"我叫天宇"

因为信息来自**第一轮**，不是来自什么神奇的记忆。每次 `call()` 内部三步：

```
1. 按 (userId, sessionId) 从 state 加载 context（第一次为空）
2. 把 context + 新消息拼成完整输入，发给模型
3. 模型答完，把新一轮两条消息追加进 context，写回 state
```

所以第二轮发给模型的内容是 `[system, 第一轮的话, 第一轮的回答, 第二轮的问题]`——模型是**当场重读了一遍聊天记录**，不是"记得"。

四份铁证（都能在自己机器上亲眼看）：

1. `agent_state.json` 的 `context` 数组里逐条躺着所有消息，连每条的 token 消耗都有；
2. **token 对账**：第一轮输入 5637 tokens，第二轮 5873 tokens，多出来的约等于第一轮两条消息的体量——证明第二轮输入里真的带着第一轮原文；
3. `FirstAgentTest` 里有一条断言：第二次发给模型的内容必须包含"我叫天宇"——而测试用的是内存 state + 只有 AGENTS.md 的临时目录，照样通过，说明来源只能是 state；
4. **时间线排除记忆文件**：memory 笔记落盘时间（06:20:11）晚于第二轮回答（06:20:10），记忆文件当时还不存在，物理上不可能参与。

## 3. 三个删目录实验（理解分层最快的路径）

### 实验 A：删游戏目录，留存档（已做过）

```bat
rmdir /s /q .agentscope
```

重跑 `FirstAgent`，结果：**agent 还认得天宇**（从用户目录的存档恢复，转录也重新导出，消息 ID 都不变），但 `AGENTS.md` 没了，agent 像变了个人格。
结论：恢复靠 state，不靠 workspace。

### 实验 B：删存档，留游戏目录（建议补做一次）

```bat
rmdir /s /q "C:\Users\你\.agentscope\state\note-taker"
```

预期：发给模型的只有 SYSTEM + USER 两条，**agent 不会自动记得天宇**；但人格（AGENTS.md）还在。
注意：用真模型时它**可能**调用 `session_history` 工具翻档案答对——那是"查到"，不是"记得"。

### 实验 C：纯 UT 验证转录不自动恢复（不用 API Key）

```bash
mvn test -Dtest=WorkspaceTranscriptOnlyTest
```

预先摆好一份旧转录、给一个全新的 state → 断言旧内容不会进入模型请求。这就是"转录只写不读（自动意义上）"的确定性证明。

## 4. 转录"只写不读"的准确含义，以及记它干嘛

准确说法：转录**不会自动装回对话**，但它有三类真实读者：

1. **agent 自己**——默认工具里有 `session_search` / `session_list` / `session_history`，翻的就是这些文件；
2. **人**——调试、审计、看产物学习。我们发现 agent 偷偷调用了 `glob_files`、发现记忆里的幻觉日期"2024-06-18"，全是打开转录文件读出来的；
3. **压缩的安全网**——`FirstAgent` 里配的 `CompactionConfig(30, 10)`：消息到 30 条时 state 里的对话被压成摘要、只留最近 10 条原文（因为模型窗口装不下无限对话）。敢压，就是因为全量原文在转录里永不丢失。

另外注意：**memory 不是只写的**。`memory/*.md` 会被后台任务合并进 `MEMORY.md`，而 `MEMORY.md` 每轮推理都注入 system prompt——记忆是走"提示词"这条路回流进对话的，不走"对话历史"那条路。

三个存储的分工一句话：

- **state** = 白板，回答"这场会怎么接着聊"；
- **sessions 转录** = 档案柜，回答"过去说过什么，能不能查到"；
- **memory** = 工作手册，回答"从过去提炼出了什么经验，每轮都带在身上"。

## 5. 坑位清单（踩过的坑，别再踩）

1. **`AGENTS.md` 是手写的种子文件，框架永远不会帮你生成**。删整个 `.agentscope` 会连它一起删，项目没 git 就无处恢复。想清运行产物：只删 `workspace\alice\`，留住 `workspace\AGENTS.md`。
2. **state 按 agent 名全局共享**（`~/.agentscope/state/note-taker/`），不区分项目。两个项目的 agent 都叫 `note-taker` 会互相串会话。想隔离：换 agent 名，或用 `.stateStore(...)` 指到项目内。
3. 想彻底重置会话：删 state 目录，或换个 `userId` / `sessionId`。
4. 跑 `main` 要设 `DASHSCOPE_API_KEY`；跑 UT 不用（fake model + 内存 state，不碰网络）。

## 6. 默认 23 个工具名单（顺带收获，后面章节的预告）

`agent_send, agent_list, read_file, load_skill_through_path, session_search, task_output, agent_spawn, execute, wait_async_results, session_list, web_search, web_fetch, memory_search, task_cancel, memory_get, grep_files, edit_file, write_file, task_list, list_files, memory_save, glob_files, session_history`

看点：文件类（read/write/edit/glob/grep/list）、会话检索类（session_*）、记忆类（memory_*）、联网类（web_*）、子 agent 类（agent_spawn/agent_send）、后台任务类（task_*）——正好对应路线图后面一大半章节。

## 7. 工作区目录解剖：alice/ 下面每个目录是谁写的、会不会压缩

```
workspace\
├─ AGENTS.md                          ← 你手写的（共享，全用户可见的人格/约定）
├─ alice\                             ← alice 的个人空间（NamespaceFactory 默认命名空间 = [userId]）
│  ├─ hello.md 之类的用户文件          ← 模型调 write_file 等文件工具写的，直接落在 alice\ 下
│  │                                     （UserWorkspaceDirTest 实验证明；"workspace\子目录"是模型自己起的名字）
│  ├─ agents\note-taker\sessions\     ← 消息级转录：SessionTranscriptWriter 写
│  │   ├─ demo-session.jsonl          ← 对话消息列表（官网原话：永不压缩的对话日志）
│  │   ├─ demo-session.log.jsonl      ← 追加式日志
│  │   └─ sessions.json               ← 会话索引
│  ├─ default\note-taker\<会话>\events\ ← 事件级日志：TranscriptMiddleware + FilesystemTranscriptStore 写
│  │   └─ 0-3-xxxx.jsonl             ← 事件段文件，文件名 = 起始序号-结束序号-随机ID
│  │                                     "default" 是默认租户名（builder.transcriptTenant 可改）
│  └─ memory\2026-09-21.md            ← 记忆钩子写的当日记忆笔记
```

**agents/ 和 default/ 的区别**：存的东西看着像，其实是两种视角——`agents/.../sessions/` 存**消息**（这轮谁说了什么，给人看、给 session_history 工具查），`default/.../events/` 存**事件**（流式增量、工具调用生命周期等 AgentEvent 的可回放记录）。一个按"消息"组织，一个按"事件序号段"组织。

**哪些会压缩、哪些不会**：

| 东西 | 会不会压缩 | 说明 |
|---|---|---|
| state 里的对话缓冲（`~/.agentscope/state/.../agent_state.json` 的 context） | **会**（唯一真正压缩的地方） | `CompactionConfig(30, 10)`：到 30 条消息压成摘要、只留最近 10 条原文——模型窗口装不下无限对话 |
| `agents/.../sessions/*.jsonl` 消息转录 | **永不压缩** | 官网原话；正因为档案全量在，压缩才敢下手 |
| `default/.../events/*.jsonl` 事件段 | 有 compact 机制 | `FilesystemTranscriptStore` 有 `compact()`（字节码可见 "compacted" 标记和段文件正则），属于段文件的内部整理，具体策略到"上下文压缩"案例再验证 |
| `memory/*.md` | 不压缩，是"追加+合并" | 每日文件只追加，后台 MemoryConsolidator 提炼进 MEMORY.md（是摘要整理，不是压缩删除） |
| 大工具结果 | 可配置卸载 | `ToolResultEvictionConfig`，路线图后续案例 |

## 8. 记忆的三层归属：每轮到底往提示词里塞了什么（MemoryInjectionTest 实验证明）

先纠正一个容易看错的点：**memory 目录其实区分用户**——路径是 `workspace/<userId>/memory/`
（`WorkspaceManager.getMemoryDir` 按 RuntimeContext 解析）。它不区分的是**会话**和 **agent**，
这是有意设计：记忆跟着"人"走，不跟着"某场对话"或"某个程序"走。

三层记忆，各有归属：

| 层 | 存哪 | 寻址键 | 寿命 |
|---|---|---|---|
| 会话记忆（这场对话说了啥） | state 的 context | `(userId, sessionId)` | 会话级，压缩时会被摘要 |
| 用户长期记忆（这个人是谁、喜欢什么） | `workspace/<userId>/MEMORY.md`（原料在 `<userId>/memory/`） | `userId` | 跨会话、跨 agent 积累 |
| 共享人格/知识（公共约定） | 工作区根 `AGENTS.md`、`knowledge/` | 无（全用户共享） | 跟着项目走 |

每轮 `call()` 的提示词拼装公式（实验断言逐条验证过）：

```
SYSTEM = sysPrompt + AGENTS.md（共享） + 该用户的 MEMORY.md（整份注入）
对话历史 = state 的 context，按 (userId, sessionId) 取
新消息 = 本轮 UserMessage
```

**"怎么知道该加载哪部分"的答案朴素得出人意料**：没有任何聪明的挑选算法，全靠路径寻址——
userId 决定读哪个用户的 MEMORY.md，sessionId 决定加载哪份对话 context。两个反例同样重要：

- `memory/` 每日原料文件**不**自动注入（实验放了个 DAILY-RAW-MARKER，system prompt 里没有）——
  只有被后台 MemoryConsolidator 提炼进 MEMORY.md 的内容才进提示词；
- sessions 转录也不自动注入（见第 4 节）——想用旧对话就调 `session_history` 工具。

所以模型"记得你"分两条路：**自动路**（MEMORY.md 整份进 system prompt）和**主动路**
（模型自己调 memory_search / session_history 翻档案）。

**MEMORY.md 太大把窗口撑爆怎么办？——三道防线（第 2 道有实验实证）**：

1. **维护端限流（平时不让它长大）**：`MemoryConfig.consolidationMaxTokens` 默认 **4000** token——
   后台 consolidator 往 MEMORY.md 合并每日记忆时按预算再蒸馏；每日文件留 90 天、会话留 180 天自动清理。
2. **注入端截断（运行时兜底）**：`WorkspaceContextMiddleware` 把 AGENTS.md + MEMORY.md + knowledge
   拼成 `<loaded_context>` 块，整体受 `maxContextTokens` 预算约束（**默认 8000** 估算 token，
   `builder.maxContextTokens(...)` 可调）；超了就 `truncateToTokenBudget` 保头去尾，并附一句
   `"(memory truncated — use memory_search for older entries)"` 引导模型走检索路。
   实验（`MemoryInjectionTest#oversizedMemoryMdIsTruncatedWithSearchHint`）：塞一份 32 万字符的
   MEMORY.md，注入的 SYSTEM 停在约 4.1 万字符（≈8000 估算 token），尾部标记没了、提示语在。
3. **对话端压缩**：`CompactionConfig` 同时有 `triggerMessages` 和 `triggerTokens`（默认量级
   50 条 / 20000 token）双触发，历史对话超预算压成结构化摘要（SESSION INTENT / SUMMARY /
   ARTIFACTS / NEXT STEPS 四段式），给固定段落腾地方。

## 9. 复现命令汇总

```bash
# 跑全部 UT（不需要 API Key）
mvn test

# 只跑"转录不自动恢复"实验
mvn test -Dtest=WorkspaceTranscriptOnlyTest

# 跑官网真实模型示例（需要 DASHSCOPE_API_KEY）
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.quickstart.FirstAgent
```

看产物：

- 对话转录：`.agentscope\workspace\alice\agents\note-taker\sessions\demo-session.jsonl`
- 记忆笔记：`.agentscope\workspace\alice\memory\`
- 存档（真正的"记忆"来源）：`C:\Users\你\.agentscope\state\note-taker\alice\demo-session\agent_state.json`
