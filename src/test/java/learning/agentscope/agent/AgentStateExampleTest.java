package learning.agentscope.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 状态与会话的确定性 UT：
 * 1. JsonFileAgentStateStore 跨 agent 实例恢复对话（"重启不丢会话"的原理）；
 * 2. 状态文件按 root/<agentId>/<userId>/<sessionId>/agent_state.json 落盘，内容可读；
 * 3. clearContext 清空会话，下一轮从零开始；
 * 4. store.listSessionIds 能枚举某用户的会话。
 */
class AgentStateExampleTest {

    @TempDir Path tempDir;

    @Test
    void jsonFileStoreRestoresAcrossAgentInstances() throws Exception {
        Path stateRoot = tempDir.resolve("state");
        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("s-1").build();

        // 实例一：说名字，关闭（状态应已写入 JSON 文件）。
        RecallModel modelOne = new RecallModel();
        try (ReActAgent first = AgentStateExample.buildAgent(modelOne, stateRoot)) {
            first.call(List.of(new UserMessage("我叫天宇。")), context).block();
        }

        // 实例二：全新对象、同一个状态目录——记忆应接上。
        RecallModel modelTwo = new RecallModel();
        try (ReActAgent second = AgentStateExample.buildAgent(modelTwo, stateRoot)) {
            Msg recall = second.call(List.of(new UserMessage("我叫什么？")), context).block();
            assertEquals("你叫天宇。", recall.getTextContent());
            assertTrue(
                    modelTwo.requests.get(0).stream()
                            .anyMatch(m -> m.getTextContent().contains("天宇")),
                    "实例二的请求应带着实例一的对话");
        }
    }

    @Test
    void stateFileLandsUnderAgentUserSessionLayout() throws Exception {
        Path stateRoot = tempDir.resolve("state");
        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("s-1").build();

        try (ReActAgent agent = AgentStateExample.buildAgent(new RecallModel(), stateRoot)) {
            agent.call(List.of(new UserMessage("我叫天宇。")), context).block();
        }

        // 产物布局（实测）：自定义根目录时为 root/<userId>/<sessionId>/agent_state.json——
        // 没有 <agentId> 层！默认的 ~/.agentscope/state/<agentId>/... 里那层 agent 名，
        // 是因为默认根目录是全机器多 agent 共享的，需要 agent 名防碰撞（案例一踩过的坑）。
        try (var walk = Files.walk(stateRoot)) {
            walk.forEach(p -> System.out.println("[tree] " + stateRoot.relativize(p)));
        }
        Path stateFile = stateRoot
                .resolve("alice").resolve("s-1")
                .resolve("agent_state.json");
        assertTrue(Files.exists(stateFile), "状态文件应存在：" + stateFile);
        String content = Files.readString(stateFile);
        assertTrue(content.contains("天宇"), "状态文件里应有对话原文");
        assertTrue(content.contains("\"session_id\""), "状态文件含会话元数据");
    }

    @Test
    void clearContextResetsSession() throws Exception {
        Path stateRoot = tempDir.resolve("state");
        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("s-1").build();
        RecallModel model = new RecallModel();

        try (ReActAgent agent = AgentStateExample.buildAgent(model, stateRoot)) {
            agent.call(List.of(new UserMessage("我叫天宇。")), context).block();

            // 清空会话：下一轮从零开始（模型请求里不再有旧对话）。
            agent.clearContext(context);
            Msg after = agent.call(List.of(new UserMessage("我叫什么？")), context).block();
            assertEquals("当前会话中没有足够信息。", after.getTextContent());
        }

        // store 侧也能枚举会话：alice 名下有 s-1。
        var store = new io.agentscope.core.state.JsonFileAgentStateStore(stateRoot);
        assertTrue(store.listSessionIds("alice").contains("s-1"), "listSessionIds 应包含 s-1");
    }

    /** 记得/不记得 fake model：请求里出现"天宇"才答得出。 */
    private static final class RecallModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            boolean knows =
                    messages.stream().anyMatch(m -> m.getTextContent().contains("天宇"));
            String answer = knows ? "你叫天宇。" : "当前会话中没有足够信息。";
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(answer).build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-recall-model";
        }
    }
}
