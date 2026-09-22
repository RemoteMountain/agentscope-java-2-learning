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

/**
 * AgentScope Java 2.0 快速开始：第一个智能体。
 *
 * <p>这个类刻意保留官网示例的结构：用 HarnessAgent builder 配置工作区、人格、模型和
 * 对话压缩，再通过 RuntimeContext 选择用户与会话。运行 main 前需要设置 DASHSCOPE_API_KEY。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/quickstart.html#id3
 */
public final class FirstAgent {

    private static final Path WORKSPACE = Paths.get(".agentscope/workspace");

    private FirstAgent() {}

    /**
     * 创建官网快速开始中的 HarnessAgent。
     *
     * <p>不显式传入 AgentStateStore 时，HarnessAgent 会使用默认的
     * JsonFileAgentStateStore，状态写入用户目录下的 ~/.agentscope/state/<agentId>/。
     */
    public static HarnessAgent buildAgent() {
        return HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                // ModelRegistry 根据字符串解析模型，并自动读取 DASHSCOPE_API_KEY。
                .model("dashscope:qwen-plus")
                .workspace(WORKSPACE)
                // 30 条消息触发压缩，压缩后保留最近 10 条原文。
                .compaction(
                        CompactionConfig.builder()
                                .triggerMessages(30)
                                .keepMessages(10)
                                .build())
                .build();
    }

    /**
     * 为 UT 提供可替换模型、工作区和状态存储的构造入口。
     *
     * <p>生产代码优先使用无参 {@link #buildAgent()}；测试通过 fake Model 避免网络依赖。
     * 关闭记忆钩子：记忆子系统每轮结束后会异步追加一次模型调用（提炼新记忆/追加当日台账），
     * 与测试断言存在竞态，且与本案例要验证的会话恢复语义无关。
     * 开关前后的确切区别见 MemoryHooksDifferenceTest。
     */
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
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 FirstAgent。");
            return;
        }

        try (HarnessAgent agent = buildAgent()) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("demo-session").build();

            // 第一轮：告诉 agent 姓名和当天要做的事情。
            Msg first =
                    agent.call(new UserMessage("我叫天宇，今天准备一个关于 ReAct 的技术分享。"), context)
                            .block();
            System.out.println("第一轮：" + first.getTextContent());

            // 第二轮：相同 (userId, sessionId)，HarnessAgent 自动恢复上一轮状态。
            Msg second = agent.call(new UserMessage("我叫什么？我今天要干什么？"), context).block();
            System.out.println("第二轮：" + second.getTextContent());
        }
    }
}
