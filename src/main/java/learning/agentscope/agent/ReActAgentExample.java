package learning.agentscope.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AgentScope Java 2.0 智能体基础：裸 ReActAgent。
 *
 * <p>前三个案例用的 HarnessAgent 是"内核 + 全套行李"（23 个默认工具、工作区、会话持久化、
 * 记忆钩子……）。本案例拆掉行李，直接用内核 {@link ReActAgent}，对比出三件事：
 *
 * <p>1. {@code call}：跑推理-行动循环，返回最终回复（Mono&lt;Msg&gt;）；
 * <p>2. {@code observe}：只把消息塞进上下文、不触发推理（Mono&lt;Void&gt;）——
 *    典型用途是"提前告诉它一些事实"或多 agent 之间互相旁听；
 * <p>3. {@code maxIters}：ReAct 循环的最大迭代次数（默认 10），防止模型无限调工具。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html
 */
public final class ReActAgentExample {

    private ReActAgentExample() {}

    /** 本案例自带的最小工具：无参数、只读。 */
    public static final class ClockTool {

        @Tool(
                name = "get_current_time",
                description = "获取当前时间，返回 ISO_LOCAL_DATE_TIME 格式的字符串。",
                readOnly = true,
                concurrencySafe = true)
        public String getCurrentTime() {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    /**
     * 官网最小示例的 agent：字符串模型 id 由 ModelRegistry 解析并自动读
     * DASHSCOPE_API_KEY。注意与 HarnessAgent 的区别：没有 workspace、没有 stateStore
     * （状态只在内存里）、工具只有自己注册的这一个。运行 main 前设置 DASHSCOPE_API_KEY。
     */
    public static ReActAgent buildAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ClockTool());
        return ReActAgent.builder()
                .name("react-demo")
                .sysPrompt("你是一个严谨的助手，涉及时间的问题必须先调用工具查询。")
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .maxIters(5)
                .build();
    }

    /** 为 UT 提供可替换模型与迭代上限的构造入口。 */
    static ReActAgent buildAgent(Model model, int maxIters) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ClockTool());
        return ReActAgent.builder()
                .name("react-demo")
                .sysPrompt("你是一个严谨的助手，涉及时间的问题必须先调用工具查询。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    public static void main(String[] args) {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 ReActAgentExample。");
            return;
        }

        try (ReActAgent agent = buildAgent()) {
            // 1. call：需要工具的问题（ReAct 循环：思考 -> 调工具 -> 观察 -> 回答）。
            Msg answer =
                    agent.call(new UserMessage("现在几点了？用一句话回答。")).block();
            System.out.println("call 的回答：" + answer.getTextContent());

            // 2. observe：只进上下文、不触发推理（没有模型调用、没有回复）。
            agent.observe(new UserMessage("（旁听信息：用户的昵称是小鱼。）")).block();

            // 3. 下一轮 call 能"看见"observe 进来的事实，并且流式输出。
            agent.streamEvents(new UserMessage("我的昵称是什么？"))
                    .doOnNext(ReActAgentExample::printEvent)
                    .blockLast();
        }
    }

    static void printEvent(AgentEvent event) {
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> System.out.print(((TextBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START ->
                    System.out.println("\n[tool-call] " + ((ToolCallStartEvent) event).getToolCallName());
            case AGENT_RESULT ->
                    System.out.println("\n[final] " + ((AgentResultEvent) event).getResult().getTextContent());
            default -> { }
        }
    }
}
