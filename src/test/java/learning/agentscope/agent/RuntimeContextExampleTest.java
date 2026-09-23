package learning.agentscope.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * RuntimeContext 的确定性 UT：
 * 1. 字符串属性 + 类型化属性 + RuntimeContext 注入，都能在工具里读到；
 * 2. 不带 @ToolParam 的 POJO 参数直接注入类型化属性；
 * 3. 属性不持久化：换一个不带属性的新 context（同 userId/sessionId），工具读到的是空。
 */
class RuntimeContextExampleTest {

    @Test
    void stringAndTypedAttributesReachToolViaInjectedContext() {
        SingleToolModel model = new SingleToolModel("echo_meta");

        try (ReActAgent agent = RuntimeContextExample.buildAgent(model)) {
            RuntimeContext context = fullContext();
            agent.call(List.of(new UserMessage("记笔记")), context).block();
        }

        String toolResult = toolResultOf(model);
        assertTrue(toolResult.contains("user=alice"), "userId 内建字段应注入：" + toolResult);
        assertTrue(toolResult.contains("session=ctx-demo"), "sessionId 内建字段应注入");
        assertTrue(toolResult.contains("request_id=req-1001"), "字符串属性应可读：" + toolResult);
        assertTrue(toolResult.contains("tenant=tenant-7"), "类型化属性应可读：" + toolResult);
    }

    @Test
    void pojoToolParameterIsInjectedFromTypedAttribute() {
        SingleToolModel model = new SingleToolModel("echo_tenant");

        try (ReActAgent agent = RuntimeContextExample.buildAgent(model)) {
            agent.call(List.of(new UserMessage("查租户")), fullContext()).block();
        }

        String toolResult = toolResultOf(model);
        assertTrue(
                toolResult.contains("tenant=tenant-7@cn-east"),
                "不带 @ToolParam 的 POJO 参数应由框架从类型化属性注入：" + toolResult);
    }

    /**
     * 属性的存活边界（实验实测）：
     * 同一个 (userId, sessionId) 再 call 时，框架复用缓存的会话上下文——第一轮 put 的属性仍可读；
     * 换一个 sessionId，属性就不在了（全新上下文）。全程无 stateStore，属性也从不落盘。
     */
    @Test
    void attributesStayInSessionSlotButNotInNewSession() {
        SingleToolModel model = new SingleToolModel("echo_meta");

        try (ReActAgent agent = RuntimeContextExample.buildAgent(model)) {
            // 第一轮：带全部属性。
            agent.call(List.of(new UserMessage("记笔记")), fullContext()).block();
            // 第二轮：同一个 (userId, sessionId)，context 对象是新的——属性仍可读（会话槽位缓存）。
            RuntimeContext sameSession = RuntimeContext.builder()
                    .userId("alice").sessionId("ctx-demo").build();
            agent.call(List.of(new UserMessage("再记一条")), sameSession).block();
            // 第三轮：换 sessionId——属性消失，拿的是全新上下文。
            RuntimeContext newSession = RuntimeContext.builder()
                    .userId("alice").sessionId("ctx-demo-2").build();
            agent.call(List.of(new UserMessage("换个会话再记")), newSession).block();
        }

        String sameSessionResult = toolResultText(model.requests.get(3));
        assertTrue(sameSessionResult.contains("request_id=req-1001"),
                "同会话槽位内属性仍可读（缓存复用）：" + sameSessionResult);

        String newSessionResult = toolResultText(model.requests.get(5));
        assertTrue(newSessionResult.contains("user=alice"), "内建字段仍在（新 context 自带）");
        assertTrue(newSessionResult.contains("request_id=null"),
                "跨会话属性不跟随：" + newSessionResult);
        assertTrue(newSessionResult.contains("tenant=无"), "跨会话类型化属性不跟随：" + newSessionResult);
    }

    private static RuntimeContext fullContext() {
        return RuntimeContext.builder()
                .userId("alice")
                .sessionId("ctx-demo")
                .put("request_id", "req-1001")
                .put(RuntimeContextExample.TenantInfo.class,
                        new RuntimeContextExample.TenantInfo("tenant-7", "cn-east"))
                .build();
    }

    /** 第二次模型请求里 TOOL 消息的文本结果。 */
    private static String toolResultOf(SingleToolModel model) {
        return toolResultText(model.requests.get(1));
    }

    private static String toolResultText(List<Msg> request) {
        // 注意取"最后一个"工具结果：多轮对话的请求里还带着历史轮次的工具结果。
        return request.stream()
                .filter(m -> m.getRole() == MsgRole.TOOL)
                .flatMap(m -> m.getContent().stream())
                .filter(b -> b instanceof ToolResultBlock)
                .map(b -> ((ToolResultBlock) b).getOutput().toString())
                .reduce((first, second) -> second)
                .orElse("");
    }

    /**
     * 单工具 fake model：第一次调用指定的工具，之后一律给最终回答。
     * 注意 ToolUseBlock 要用五参构造器带上 content JSON（框架参数校验读它）。
     */
    private static final class SingleToolModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();
        private final String toolName;
        private int calls;

        SingleToolModel(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            calls++;
            if (calls % 2 == 1) {
                String argsJson = "{\"content\":\"hi\"}";
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(new ToolUseBlock(
                                        "call-1", toolName, Map.of("content", "hi"),
                                        argsJson, Map.of())))
                                .finishReason("tool_calls")
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("完成。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-single-tool-model";
        }
    }
}
