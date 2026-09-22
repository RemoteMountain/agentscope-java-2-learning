package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 流式查看案例的确定性 UT。
 *
 * <p>fake model 分两步模拟“先调工具、再给最终回答”的完整智能体循环：
 * 第一次调用只发出 ToolUseBlock（发起工具调用）；框架执行工具、把结果以 TOOL 消息
 * 塞回上下文后发起第二次调用；第二次调用流式吐出若干文本块作为最终回答。
 *
 * <p>断言覆盖官网示例关心的三件事：文本增量事件、工具调用开始事件、最终结果事件，
 * 另外验证事件顺序（最终文本一定出现在工具结果之后）和第二次请求里确实有工具结果。
 */
class StreamingFirstAgentTest {

    @TempDir Path tempDir;

    @Test
    void streamEventsExposesDeltasToolCallAndFinalResult() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new StreamingFirstAgent.ClockTool());
        FakeStreamingModel model = new FakeStreamingModel();

        try (var agent =
                StreamingFirstAgent.buildAgent(
                        model, toolkit, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("streaming-demo").build();

            List<AgentEvent> events =
                    agent.streamEvents(new UserMessage("现在几点了？"), context)
                            .collectList()
                            .block();

            assertCompleteLoop(events, model);
        }
    }

    private static void assertCompleteLoop(List<AgentEvent> events, FakeStreamingModel model) {
        // 1. 模型被调用了两次：第一次发起工具调用，第二次拿着工具结果给出最终回答。
        //    （UT 构造入口已 disableMemoryHooks；开启时每轮结束后还会额外发起
        //    "Extract NEW memories..."/"Today's daily ledger..."的记忆模型调用。）
        assertEquals(2, model.requests.size());

        // 2. 第二次发给模型的请求里，上一轮的工具结果已作为 TOOL 消息进入上下文。
        assertEquals(
                1,
                model.requests.get(1).stream()
                        .filter(message -> message.getRole() == MsgRole.TOOL)
                        .count());

        // 3. 工具调用开始事件：名字来自 @Tool 注解，id 来自 ToolUseBlock。
        ToolCallStartEvent toolCall =
                events.stream()
                        .filter(event -> event.getType() == AgentEventType.TOOL_CALL_START)
                        .map(event -> (ToolCallStartEvent) event)
                        .findFirst()
                        .orElseThrow();
        assertEquals(StreamingFirstAgent.TOOL_NAME, toolCall.getToolCallName());
        assertEquals("call-1", toolCall.getToolCallId());

        // 4. 文本增量事件全部拼接 == 最终回答 == AGENT_RESULT 里的 Msg。
        String streamedText =
                events.stream()
                        .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                        .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                        .reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
                        .toString();
        Msg finalResult =
                events.stream()
                        .filter(event -> event.getType() == AgentEventType.AGENT_RESULT)
                        .map(event -> ((AgentResultEvent) event).getResult())
                        .findFirst()
                        .orElseThrow();
        assertEquals(FakeStreamingModel.FINAL_ANSWER, streamedText);
        assertEquals(FakeStreamingModel.FINAL_ANSWER, finalResult.getTextContent());

        // 5. 事件顺序验证智能体循环：工具调用 -> 工具结果 -> 最终文本。
        int toolCallStart = indexOf(events, AgentEventType.TOOL_CALL_START);
        int toolResultEnd = indexOf(events, AgentEventType.TOOL_RESULT_END);
        int firstTextDelta = indexOf(events, AgentEventType.TEXT_BLOCK_DELTA);
        int agentResult = indexOf(events, AgentEventType.AGENT_RESULT);
        assertTrue(toolCallStart < toolResultEnd, "工具调用开始应在工具结果之前");
        assertTrue(toolResultEnd < firstTextDelta, "最终文本应在工具结果之后");
        assertTrue(firstTextDelta < agentResult, "文本增量应在最终结果之前");
    }

    private static int indexOf(List<AgentEvent> events, AgentEventType type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getType() == type) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Fake model：第一次调用只发起工具调用（不带文本前言，保证文本增量全部来自第二次）；
     * 第二次调用把最终回答拆成多个 TextBlock 流式吐出，模拟真实模型的增量输出。
     */
    private static final class FakeStreamingModel implements Model {

        static final String FINAL_ANSWER = "现在的时间已获取。下面是今天的三件事：一、二、三。";

        private final List<List<Msg>> requests = new ArrayList<>();
        private int calls;

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            calls++;
            if (calls == 1) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                new ToolUseBlock(
                                                        "call-1",
                                                        StreamingFirstAgent.TOOL_NAME,
                                                        java.util.Map.of())))
                                .finishReason("tool_calls")
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("现在的时间已获取。").build()))
                            .build(),
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("下面是今天的三件事：一、二、三。")
                                                    .build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-streaming-model";
        }
    }
}
