package learning.agentscope.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
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
 * Tool 案例的确定性测试：
 * 1. @Tool / @ToolParam 会生成给模型看的工具 Schema；
 * 2. 工具成功结果会回到下一次模型请求；
 * 3. 工具抛出的异常会变成 ERROR 结果，而不是让整个 Agent 循环崩溃。
 */
class ToolExampleTest {

    @Test
    void annotationsProduceToolSchemasWithRequiredParameters() {
        var schemas = ToolExample.buildToolkit().getToolSchemas();

        ToolSchema add = schemas.stream()
                .filter(schema -> schema.getName().equals("add_numbers"))
                .findFirst()
                .orElseThrow();

        assertEquals("两个整数相加，返回和。", add.getDescription());
        assertEquals("object", add.getParameters().get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) add.getParameters().get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("left"));
        assertTrue(properties.containsKey("right"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) add.getParameters().get("required");
        assertEquals(List.of("left", "right"), required);
    }

    @Test
    void successfulToolResultIsFedBackBeforeFinalAnswer() {
        ScriptedModel model = new ScriptedModel(List.of(
                toolResponse(
                        "call-add",
                        "add_numbers",
                        Map.of("left", 3, "right", 4),
                        "{\"left\":3,\"right\":4}"),
                textResponse("结果是 7。")));

        try (ReActAgent agent = ToolExample.buildAgent(model)) {
            Msg result = agent.call(new UserMessage("计算 3 加 4")).block();

            assertEquals("结果是 7。", result.getTextContent());
            assertEquals(2, model.requests.size(), "工具调用后应再请求模型生成最终回答");

            ToolResultBlock toolResult = lastToolResult(model.requests.get(1));
            assertEquals(ToolResultState.SUCCESS, toolResult.getState());
            assertTrue(toolResult.getOutput().toString().contains("7"));
        }
    }

    @Test
    void toolExceptionBecomesErrorResultAndAgentCanContinue() {
        ScriptedModel model = new ScriptedModel(List.of(
                toolResponse(
                        "call-divide",
                        "divide_numbers",
                        Map.of("dividend", 10, "divisor", 0),
                        "{\"dividend\":10,\"divisor\":0}"),
                textResponse("不能除以零。")));

        try (ReActAgent agent = ToolExample.buildAgent(model)) {
            Msg result = agent.call(new UserMessage("计算 10 除以 0")).block();

            assertEquals("不能除以零。", result.getTextContent());
            ToolResultBlock toolResult = lastToolResult(model.requests.get(1));
            assertEquals(ToolResultState.ERROR, toolResult.getState());
            assertTrue(toolResult.getOutput().toString().contains("0"));
        }
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
            return "fake-tool-model";
        }
    }
}
