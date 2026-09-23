package learning.agentscope.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 裸 ReActAgent 的确定性 UT，验证官网 building-blocks/agent 一章的三个知识点：
 * 1. call 的推理-行动循环在"无行李"内核上照常工作，且工具只有自己注册的；
 * 2. observe 只把消息放进上下文、不触发任何模型调用；
 * 3. maxIters 限制 ReAct 循环迭代次数，超限后发 EXCEED_MAX_ITERS 事件并终止。
 */
class ReActAgentExampleTest {

    @Test
    void bareAgentRunsToolLoopWithOnlyRegisteredTools() {
        ScriptedModel model = new ScriptedModel();
        // 脚本：第一次发起工具调用，第二次给出最终回答。
        model.script.add(
                ChatResponse.builder()
                        .content(List.of(new ToolUseBlock("call-1", "get_current_time",
                                Map.of(), "{}", Map.of())))
                        .finishReason("tool_calls")
                        .build());
        model.script.add(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("现在是 2026-09-23T10:00:00。").build()))
                        .finishReason("stop")
                        .build());

        try (ReActAgent agent = ReActAgentExample.buildAgent(model, 10)) {
            Msg answer = agent.call(new UserMessage("现在几点了？")).block();

            assertEquals("现在是 2026-09-23T10:00:00。", answer.getTextContent());
            assertEquals(2, model.requests.size(), "两次模型调用：发起工具 + 最终回答");
            assertTrue(
                    model.requests.get(1).stream().anyMatch(m -> m.getRole() == MsgRole.TOOL),
                    "第二次请求应包含工具结果");
        }
    }

    @Test
    void bareAgentHasNoDefaultTools() {
        ScriptedModel model = new ScriptedModel();

        try (ReActAgent agent = ReActAgentExample.buildAgent(model, 10)) {
            agent.call(new UserMessage("你好")).block();

            // 对比 HarnessAgent 的 23 个默认工具：裸内核只有自己注册的这一个。
            assertEquals(1, model.lastToolsSeen.size(), "只注册了 1 个工具");
            assertEquals("get_current_time", model.lastToolsSeen.get(0).getName());
        }
    }

    @Test
    void observeAddsContextWithoutReasoning() {
        ScriptedModel model = new ScriptedModel();

        try (ReActAgent agent = ReActAgentExample.buildAgent(model, 10)) {
            // observe：不触发推理——模型一次都不该被调用。
            agent.observe(new UserMessage("（旁听信息：用户的昵称是小鱼。）")).block();
            assertEquals(0, model.requests.size(), "observe 不应触发模型调用");

            // 之后的 call 能"看见"observe 进来的事实：无 stateStore 时上下文在内存里延续。
            Msg answer = agent.call(new UserMessage("我的昵称是什么？")).block();
            assertEquals("你的昵称是小鱼。", answer.getTextContent());
            assertTrue(
                    model.requests.get(0).stream()
                            .anyMatch(m -> m.getTextContent().contains("小鱼")),
                    "observe 的消息应已在上下文中");
        }
    }

    @Test
    void maxItersStopsInfiniteToolLoop() {
        // 脚本：永远发起工具调用，永不给最终回答（死循环模型）。
        LoopForeverModel model = new LoopForeverModel();

        try (ReActAgent agent = ReActAgentExample.buildAgent(model, 2)) {
            List<AgentEvent> events =
                    agent.streamEvents(new UserMessage("查一下时间")).collectList().block();

            // 超限语义（事件序列实测）：2 次带工具的迭代 -> EXCEED_MAX_ITERS ->
            // 没收工具后再给 1 次收尾调用 -> 仍然产出 AGENT_RESULT。
            assertTrue(
                    events.stream().anyMatch(e -> e.getType() == AgentEventType.EXCEED_MAX_ITERS),
                    "超限应发出 EXCEED_MAX_ITERS 事件");
            assertEquals(3, model.requests.size(), "2 次迭代 + 1 次没收工具的收尾调用");
            assertTrue(
                    model.toolsByCall.get(2).isEmpty(),
                    "收尾调用不应再带任何工具——模型被强制用纯文本作答");
            assertTrue(
                    events.stream().anyMatch(e -> e.getType() == AgentEventType.AGENT_RESULT),
                    "超限后仍有最终结果（收尾调用的产出）");
        }
    }

    /**
     * 按脚本回复的 fake model：脚本耗尽后一律回答固定文案；同时记录工具列表。
     */
    private static final class ScriptedModel implements Model {
        private final List<ChatResponse> script = new ArrayList<>();
        private final List<List<Msg>> requests = new ArrayList<>();
        private List<ToolSchema> lastToolsSeen = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            lastToolsSeen = List.copyOf(tools);
            ChatResponse response =
                    script.isEmpty()
                            ? ChatResponse.builder()
                                    .content(List.of(TextBlock.builder()
                                            .text("你的昵称是小鱼。").build()))
                                    .finishReason("stop")
                                    .build()
                            : script.remove(0);
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return "fake-scripted-model";
        }
    }

    /** 死循环模型：每次都发起工具调用，验证 maxIters 的熔断；记录每次调用拿到的工具列表。 */
    private static final class LoopForeverModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();
        private final List<List<ToolSchema>> toolsByCall = new ArrayList<>();
        private int seq;

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            toolsByCall.add(List.copyOf(tools));
            seq++;
            String argsJson = "{}";
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(new ToolUseBlock("call-" + seq,
                                    "get_current_time", Map.of(), argsJson, Map.of())))
                            .finishReason("tool_calls")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-loop-forever-model";
        }
    }
}
