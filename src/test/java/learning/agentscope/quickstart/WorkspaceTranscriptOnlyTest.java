package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * 回答一个问题：workspace 里的会话转录（sessions/demo-session.jsonl）会不会自动恢复进对话？
 *
 * <p>实验设计：工作区里预置一份旧会话转录（按框架真实布局），但状态存储是全新的
 * （等价于删掉 ~/.agentscope/state/note-taker 后重跑）。如果转录会自动恢复，
 * 发给模型的消息里就应该出现旧对话；反之则只有本轮新消息。
 *
 * <p>对照证据：真实运行中删除工作区、保留状态目录，会话能完整恢复且消息 ID 不变，
 * 说明恢复的来源是 AgentStateStore，不是转录文件。
 */
class WorkspaceTranscriptOnlyTest {

    @TempDir Path tempDir;

    @Test
    void transcriptAloneDoesNotRestoreConversation() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");

        // 按真实运行产物的布局预置“旧对话转录”。
        Path sessionsDir =
                Files.createDirectories(workspace.resolve("alice/agents/note-taker/sessions"));
        Files.writeString(
                sessionsDir.resolve("demo-session.jsonl"),
                "{\"type\":\"message\",\"id\":\"old-user\",\"timestamp\":1789021209.0,"
                        + "\"role\":\"USER\",\"content\":\"我叫天宇，今天准备一个关于 ReAct 的技术分享。\"}\n"
                        + "{\"type\":\"message\",\"id\":\"old-assistant\",\"parentId\":\"old-user\","
                        + "\"timestamp\":1789021209.0,\"role\":\"ASSISTANT\",\"content\":\"已记录。\"}\n");
        Files.writeString(
                sessionsDir.resolve("sessions.json"),
                "{\"sessions\":{\"demo-session\":{\"summary\":\"transcript updated (2 entries)\","
                        + "\"updatedAt\":\"2026-09-10T06:20:10Z\"}},\"version\":1}");

        RecordingModel model = new RecordingModel();
        try (var agent = FirstAgent.buildAgent(model, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("demo-session").build();
            agent.call(new UserMessage("我叫什么？"), context).block();
        }

        List<Msg> request = model.requests.get(0);
        System.out.println("发给模型的消息角色: " + rolesOf(request));
        System.out.println(
                "agent 可用工具: "
                        + model.toolsSeen.stream()
                                .map(ToolSchema::getName)
                                .toList());

        // 核心断言：旧转录内容不会自动进入模型请求。
        assertFalse(
                request.stream().anyMatch(message -> message.getTextContent().contains("天宇")),
                "转录文件不应被自动恢复进对话");
        assertEquals(1, request.stream().filter(m -> m.getRole() == MsgRole.USER).count());

        // 观察点：调用之后转录文件变成了什么？（打印，不断言——用于理解“转录镜像状态”）
        System.out.println("调用后 demo-session.jsonl 行数: "
                + Files.readAllLines(sessionsDir.resolve("demo-session.jsonl")).size());
    }

    private static List<String> rolesOf(List<Msg> messages) {
        List<String> roles = new ArrayList<>();
        for (Msg message : messages) {
            roles.add(String.valueOf(message.getRole()));
        }
        return roles;
    }

    /** 最小 fake model：记录收到的消息和工具列表，返回固定回复。 */
    private static final class RecordingModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();
        private List<ToolSchema> toolsSeen = List.of();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            toolsSeen = List.copyOf(tools);
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("我不认识你。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-transcript-test-model";
        }
    }
}
