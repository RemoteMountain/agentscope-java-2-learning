package learning.agentscope.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * AgentScope Java 2.0：状态与会话（AgentState 与 AgentStateStore）。
 *
 * <p>案例一用"删目录实验"摸过这套机制，本案例正面验证它：AgentState（对话缓冲、权限、
 * 任务等运行时快照）按 (userId, sessionId) 寻址存在 AgentStateStore 里；换 store 实现，
 * 换的就是"会话恢复"的物理载体。
 *
 * <p>演示 JsonFileAgentStateStore（官网默认，存 JSON 文件）最重要的一条性质：
 * <b>跨 agent 实例恢复</b>——第一个实例关掉（模拟进程退出），第二个实例指到同一个目录，
 * 对话记忆照常接上。这正是"重启不丢会话"的原理。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/en/docs/building-blocks/context.html（AgentState 部分）
 */
public final class AgentStateExample {

    /** main 用的状态目录：放项目里，跑完可以直接看产物。 */
    private static final Path STATE_ROOT = Paths.get(".agentscope/state-demo");

    private AgentStateExample() {}

    /** 官网默认行为：JsonFileAgentStateStore 指到项目内目录（默认是无参构造 → ~/.agentscope/state）。 */
    public static ReActAgent buildAgent() {
        return ReActAgent.builder()
                .name("state-demo")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model("dashscope:qwen-plus")
                .stateStore(new JsonFileAgentStateStore(STATE_ROOT))
                .build();
    }

    /** 为 UT 提供可替换模型与状态目录的构造入口。 */
    static ReActAgent buildAgent(Model model, Path stateRoot) {
        return ReActAgent.builder()
                .name("state-demo")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model(model)
                .stateStore(new JsonFileAgentStateStore(stateRoot))
                .build();
    }

    public static void main(String[] args) {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 AgentStateExample。");
            return;
        }

        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("state-demo").build();
        List<Msg> tell = List.of(new UserMessage("我叫天宇，正在学 AgentScope 的状态管理。"));

        // 实例一：告诉它名字，然后关闭——模拟进程退出。
        try (ReActAgent first = buildAgent()) {
            Msg reply = first.call(tell, context).block();
            System.out.println("实例一：" + reply.getTextContent());
        }

        // 实例二：全新的 agent 对象，同一个状态目录——记忆应该接上。
        try (ReActAgent second = buildAgent()) {
            Msg recall =
                    second.call(List.of(new UserMessage("我叫什么？我在学什么？")), context).block();
            System.out.println("实例二（新进程等价物）：" + recall.getTextContent());
        }

        System.out.println("\n状态文件在：" + STATE_ROOT.toAbsolutePath()
                + "\\alice\\state-demo\\agent_state.json（自定义根目录下没有 agent 名层，可直接打开看）");
    }
}
