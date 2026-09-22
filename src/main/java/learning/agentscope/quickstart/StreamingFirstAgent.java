package learning.agentscope.quickstart;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AgentScope Java 2.0 快速开始：流式查看推理与工具调用。
 *
 * <p>与 {@link FirstAgent} 的唯一区别：把 {@code call(...)} 换成 {@code streamEvents(...)}，
 * 返回的不再是一条最终回复，而是一个 Reactor 事件流（Flux）。文本增量、思考块、
 * 工具调用开始/结束、最终结果都作为事件逐个到达，适合边生成边渲染。
 *
 * <p>为了能真实看到工具调用事件，本案例注册了一个最小自定义工具 {@link ClockTool}。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/quickstart.html（流式查看推理与工具调用小节）
 */
public final class StreamingFirstAgent {

    public static final String TOOL_NAME = "get_current_time";

    private static final Path WORKSPACE = Paths.get(".agentscope/workspace");

    private StreamingFirstAgent() {}

    /** main 与 UT 共用的演示工具：无参数、只读、返回当前时间。 */
    public static final class ClockTool {

        @Tool(
                name = TOOL_NAME,
                description = "获取当前时间，返回 ISO_LOCAL_DATE_TIME 格式的字符串。",
                readOnly = true,
                concurrencySafe = true)
        public String getCurrentTime() {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    /**
     * 官网示例的 agent：DashScope 模型 + 自定义工具。
     *
     * <p>运行 main 前需要设置 DASHSCOPE_API_KEY。
     */
    public static HarnessAgent buildAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ClockTool());
        return HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .workspace(WORKSPACE)
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
     * <p>关闭记忆钩子：记忆子系统会在回合结束后异步追加模型调用，与测试断言存在竞态，
     * 且与本案例要验证的流式事件语义无关；开关的确切区别见 MemoryHooksDifferenceTest，
     * 长期记忆在路线图中有专属案例。
     */
    static HarnessAgent buildAgent(
            Model model, Toolkit toolkit, Path workspace, AgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model(model)
                .toolkit(toolkit)
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
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 StreamingFirstAgent。");
            return;
        }

        try (HarnessAgent agent = buildAgent()) {
            // 换一个 sessionId，与 FirstAgent 的 demo-session 互不干扰。
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("streaming-demo").build();

            agent.streamEvents(
                            new UserMessage("先用工具查一下现在几点，然后流式列出今天要做的三件事。"),
                            context)
                    .doOnNext(StreamingFirstAgent::printEvent)
                    .blockLast();
        }
    }

    /**
     * 官网示例的事件分支写法：{@code getType()} 判断类型，再强转成具体事件类取字段。
     * 文本/思考增量直接追加打印；工具调用和结果打标签；最终结果单独一行。
     */
    static void printEvent(AgentEvent event) {
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> System.out.print(((TextBlockDeltaEvent) event).getDelta());
            case THINKING_BLOCK_DELTA -> System.out.print(((ThinkingBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START ->
                    System.out.println(
                            "\n[tool-call] "
                                    + ((ToolCallStartEvent) event).getToolCallName()
                                    + " ("
                                    + ((ToolCallStartEvent) event).getToolCallId()
                                    + ")");
            case TOOL_RESULT_END ->
                    System.out.println(
                            "[tool-result] state=" + ((ToolResultEndEvent) event).getState());
            case AGENT_RESULT ->
                    System.out.println(
                            "\n[final] " + ((AgentResultEvent) event).getResult().getTextContent());
            default -> {
                // 其余事件（AGENT_START、TOOL_CALL_DELTA 等）本案例不逐个打印。
            }
        }
    }
}
