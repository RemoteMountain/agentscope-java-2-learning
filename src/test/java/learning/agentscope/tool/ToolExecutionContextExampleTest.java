package learning.agentscope.tool;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 工具执行上下文的确定性测试：
 * 1. RuntimeContext 中的类型化对象会自动注入 @Tool 方法，且不会出现在模型 Schema；
 * 2. ToolBase.callAsync 可以从 ToolCallParam 读取 RuntimeContext；
 * 3. 同一个 agent 的不同 call 可以使用不同的上下文对象。
 */
class ToolExecutionContextExampleTest {

    @Test
    void injectedPojoIsHiddenFromModelSchema() {
        List<ToolSchema> schemas = ToolExecutionContextExample.buildToolkit().getToolSchemas();

        ToolSchema greet = schemas.stream()
                .filter(schema -> schema.getName().equals("greet_user"))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) greet.getParameters().get("properties");
        assertTrue(properties.containsKey("greeting"));
        assertFalse(properties.containsKey("userContext"));
    }

    @Test
    void annotatedToolReceivesTypedContextForEachCall() {
        ScriptedModel model = new ScriptedModel(List.of(
                toolResponse(
                        "call-greet-alice",
                        "greet_user",
                        Map.of("greeting", "你好"),
                        "{\"greeting\":\"你好\"}"),
                textResponse("已问候 Alice。"),
                toolResponse(
                        "call-greet-bob",
                        "greet_user",
                        Map.of("greeting", "Hello"),
                        "{\"greeting\":\"Hello\"}"),
                textResponse("Bob 已收到问候。")));

        try (ReActAgent agent = ToolExecutionContextExample.buildAgent(model)) {
            agent.call(
                            List.of(new UserMessage("问候我")),
                            RuntimeContext.builder()
                                    .userId("alice")
                                    .sessionId("ctx-alice")
                                    .put(ToolExecutionContextExample.UserContext.class,
                                            new ToolExecutionContextExample.UserContext(
                                                    "alice", "zh", List.of("紧凑视图")))
                                    .build())
                    .block();
            agent.call(
                            List.of(new UserMessage("问候我")),
                            RuntimeContext.builder()
                                    .userId("bob")
                                    .sessionId("ctx-bob")
                                    .put(ToolExecutionContextExample.UserContext.class,
                                            new ToolExecutionContextExample.UserContext(
                                                    "bob", "en", List.of("large fonts")))
                                    .build())
                    .block();
        }

        assertTrue(lastToolResult(model.requests.get(1)).getOutput().toString().contains("alice"));
        assertTrue(lastToolResult(model.requests.get(3)).getOutput().toString().contains("bob"));
    }

    @Test
    void toolBaseReadsRuntimeContextFromToolCallParam() {
        ScriptedModel model = new ScriptedModel(List.of(
                toolResponse(
                        "call-audit",
                        "audit_note",
                        Map.of("note", "已查看图纸"),
                        "{\"note\":\"已查看图纸\"}"),
                textResponse("审计完成。")));

        RuntimeContext context = RuntimeContext.builder()
                .userId("alice")
                .sessionId("ctx-audit")
                .put(ToolExecutionContextExample.AuditContext.class,
                        new ToolExecutionContextExample.AuditContext("req-1001"))
                .build();

        try (ReActAgent agent = ToolExecutionContextExample.buildAgent(model)) {
            agent.call(List.of(new UserMessage("记录审计信息")), context).block();
        }

        String result = lastToolResult(model.requests.get(1)).getOutput().toString();
        assertTrue(result.contains("user=alice"));
        assertTrue(result.contains("request=req-1001"));
        assertTrue(result.contains("已查看图纸"));
    }

    private static ToolResultBlock lastToolResult(List<Msg> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.TOOL)
                .flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static ChatResponse toolResponse(
            String id, String name, Map<String, Object> input, String content) {
        return ChatResponse.builder()
                .content(List.of(new ToolUseBlock(id, name, input, content, Map.of())))
                .finishReason("tool_calls")
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build();
    }

    private static final class ScriptedModel implements Model {
        private final List<ChatResponse> script;
        private final List<List<Msg>> requests = new ArrayList<>();

        private ScriptedModel(List<ChatResponse> script) {
            this.script = new ArrayList<>(script);
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            return Flux.just(script.remove(0));
        }

        @Override
        public String getModelName() {
            return "fake-tool-context-model";
        }
    }
}
