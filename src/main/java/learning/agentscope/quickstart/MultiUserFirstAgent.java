package learning.agentscope.quickstart;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import reactor.core.publisher.Mono;

/**
 * AgentScope Java 2.0 快速开始：多用户并发。
 *
 * <p>官网要点：agent 实例在调用之间是<strong>无状态</strong>的——应用启动时创建一个实例
 * （单例即可），身份信息（userId、sessionId）随每次 {@code call} 通过 RuntimeContext 携带。
 * 框架按 (userId, sessionId) 粒度保证并发安全：同一会话的请求自动串行化（SessionTurnGate
 * 发放 TurnLease），不同会话完全并行。
 *
 * <p>官网示例的形态是 HTTP handler 里每次请求 {@code .block()}；本 main 用 {@link Mono#zip}
 * 把两路调用同时发起，等价于两个并发到达的请求。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/quickstart.html（多用户并发小节）
 */
public final class MultiUserFirstAgent {

    private static final Path WORKSPACE = Paths.get(".agentscope/workspace");

    private MultiUserFirstAgent() {}

    /**
     * 官网示例的 agent：与 FirstAgent 相同的构建方式。
     *
     * <p>多用户的关键不在 builder——builder 一个字都不用改；关键在“只 build 一次，全程复用”。
     * 运行 main 前需要设置 DASHSCOPE_API_KEY。
     */
    public static HarnessAgent buildAgent() {
        return HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model("dashscope:qwen-plus")
                .workspace(WORKSPACE)
                .compaction(
                        CompactionConfig.builder()
                                .triggerMessages(30)
                                .keepMessages(10)
                                .build())
                .build();
    }

    /** 为 UT 提供可替换模型、工作区和状态存储的构造入口。 */
    static HarnessAgent buildAgent(Model model, Path workspace, AgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model(model)
                .workspace(workspace)
                .stateStore(stateStore)
                .disableMemoryHooks()
                .compaction(
                        CompactionConfig.builder()
                                .triggerMessages(30)
                                .keepMessages(10)
                                .build())
                .build();
    }

    public static void main(String[] args) {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 MultiUserFirstAgent。");
            return;
        }

        // 单例：整个应用只有一个 agent 实例，身份随每次 call 携带。
        try (HarnessAgent agent = buildAgent()) {
            RuntimeContext alice =
                    RuntimeContext.builder().userId("alice").sessionId("multi-demo").build();
            RuntimeContext bob =
                    RuntimeContext.builder().userId("bob").sessionId("multi-demo").build();

            System.out.println("== 第一轮：两个用户同时提问（模拟两个并发请求到达同一个实例）==");
            Mono.zip(
                            agent.call(new UserMessage("我叫天宇，在准备一场 ReAct 技术分享。"), alice),
                            agent.call(new UserMessage("我叫小马，在准备期末考试。"), bob))
                    .map(
                            pair ->
                                    "alice 得到：" + pair.getT1().getTextContent()
                                            + "\nbob 得到：" + pair.getT2().getTextContent())
                    .block();

            System.out.println("\n== 第二轮：各自回忆，互不串台 ==");
            Msg aliceRecall =
                    agent.call(new UserMessage("我叫什么？我在准备什么？"), alice).block();
            Msg bobRecall = agent.call(new UserMessage("我叫什么？我在准备什么？"), bob).block();
            System.out.println("alice：" + aliceRecall.getTextContent());
            System.out.println("bob：" + bobRecall.getTextContent());

            System.out.println(
                    "\n产物按用户落盘：workspace/alice/ 与 workspace/bob/ 各自独立，见 .agentscope 目录。");
        }
    }
}
