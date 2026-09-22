package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 快速开始案例的确定性 UT。
 *
 * <p>官网示例使用真实 DashScope 模型；这里用一个最小 fake model，只验证 HarnessAgent
 * 的会话状态行为，不让测试依赖网络或 API Key。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/quickstart.html#id3
 */
class FirstAgentTest {

    @TempDir Path tempDir;

    @Test
    void sameUserAndSessionRestorePreviousConversation() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        RecordingModel model = new RecordingModel();

        try (var agent = FirstAgent.buildAgent(model, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("demo-session").build();

            Msg first = agent.call(new UserMessage("我叫天宇，今天准备 ReAct 分享。"), context).block();
            Msg second = agent.call(new UserMessage("我叫什么？我今天要干什么？"), context).block();

            assertEquals("好的，我记住了。", first.getTextContent());
            assertEquals("你叫天宇，今天准备关于 ReAct 的技术分享。", second.getTextContent());
            assertEquals(2, model.requests.size());
            assertTrue(
                    model.requests.get(1).stream()
                            .anyMatch(message -> message.getTextContent().contains("我叫天宇")));
        }
    }

    @Test
    void differentSessionsDoNotShareConversationState() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        RecordingModel model = new RecordingModel();

        try (var agent = FirstAgent.buildAgent(model, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext firstSession =
                    RuntimeContext.builder().userId("alice").sessionId("session-1").build();
            RuntimeContext secondSession =
                    RuntimeContext.builder().userId("alice").sessionId("session-2").build();

            agent.call(new UserMessage("我叫天宇。"), firstSession).block();
            Msg result = agent.call(new UserMessage("你知道我的名字吗？"), secondSession).block();

            assertEquals("当前会话中没有足够信息。", result.getTextContent());
            assertEquals(2, model.requests.size());
            assertEquals(1, countUserMessages(model.requests.get(1)));
        }
    }

    private static long countUserMessages(List<Msg> messages) {
        return messages.stream().filter(message -> message.getRole() == MsgRole.USER).count();
    }

    /** Fake model：第二轮只有在请求中确实带有第一轮事实时才返回“记得”。 */
    private static final class RecordingModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            boolean restoredName =
                    countUserMessages(messages) >= 2
                            && messages.stream()
                                    .anyMatch(message -> message.getTextContent().contains("天宇"));
            String answer = restoredName ? "你叫天宇，今天准备关于 ReAct 的技术分享。" : "好的，我记住了。";
            if (countUserMessages(messages) == 1
                    && messages.stream()
                            .anyMatch(message -> message.getTextContent().contains("知道我的名字"))) {
                answer = "当前会话中没有足够信息。";
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(answer).build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-first-agent-model";
        }
    }
}
