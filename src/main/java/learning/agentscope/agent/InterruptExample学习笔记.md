# InterruptExample 学习笔记（大白话版）

> 对应官网 building-blocks/agent 的 interrupt 一族方法（路线图此前漏了，本案例补上）。
> 代码：`InterruptExample.java` / `InterruptExampleTest.java`。

## 一句话总纲

**interrupt 是"举牌示意"，不是"拔电源"**：往 AgentState 的 InterruptControl 里置个标志，
循环在模型流的块间隙看到牌子就停下、优雅收尾——但正在阻塞的那一次模型调用，它拔不掉。

## 1. 机制（反编译 + 实验双重确认）

```java
agent.interrupt(context)
  → stateCache 里按 (userId, sessionId) 找到 AgentState
  → state.interruptControl().trigger(InterruptSource.USER, msg)   // 置标志

// 循环侧（ReActAgent$CallExecution）：
// 每个模型响应块之后 checkInterrupted()：
//   isInterrupted() == true → Mono.error(InterruptedException) → 循环终止
```

无参 `interrupt()` 已标记 @Deprecated——它打断的是"默认上下文"，用 `interrupt(context)`
（或 `interrupt(userId, sessionId)`）才打得准。

## 2. 生效条件（踩了三轮坑换来的，重要！）

实验从"完全不生效"到"完美生效"，差两个条件：

| 条件 | 为什么 | 后果 |
|---|---|---|
| **挂 stateStore** | 标志位存在按 (userId, sessionId) 寻址的 AgentState 里，需要 stateCache 能寻址到同一个实例 | 裸 ReActAgent（无 store）+ 同步假模型时，标志位传不到运行中的调用 |
| **模型流是异步的** | 检查点在模型流的块间隙，同步 `Flux.just` 不给调度边界 | 同步 fake model 演示不出中断效果 |

真实模型（DashScope 走 HTTP 流式）天然满足第二条。**写 UT 验证中断时，fake 模型要加
`delayElements(20ms)`**——这是本案例最大的坑位贡献。

## 3. 中断后的样子（实测）

```
[interrupt 实测] 539ms 结束，迭代 6 次（maxIters=100）
结果 = MSG: I noticed that you have interrupted me. What can I do for you?
```

- 循环被掐断在第 6 次迭代；
- 调用**不报错、正常返回**一条 Msg——连"我注意到你打断了我"这句都是框架生成的收尾语
  （fake 模型只会返回工具调用，这句话不可能来自它）；
- 对比 maxIters 超限（案例四）：那是"没收工具强制收尾"，这是"用户举牌立即收尾"。

## 4. 打不断的东西（如实记录）

阻塞中的单次模型调用（实验：模型睡 3 秒，中途 interrupt，照样等满 3 秒）。
要"硬停"只能取消订阅（`Disposable.dispose()`）——对真实流式模型这会掐断 HTTP 流；
协作式 interrupt 的价值是让 agent 有机会体面地停下来并保存状态。

## 5. 复现命令

```bash
mvn test -Dtest=InterruptExampleTest

# 真实模型：长问题跑 1.5 秒后主动打断（需要 DASHSCOPE_API_KEY）
mvn -q compile exec:java -Dexec.mainClass=learning.agentscope.agent.InterruptExample
```
