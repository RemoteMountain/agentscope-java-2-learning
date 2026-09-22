package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 回答一个问题：某个用户某个会话发起对话时，框架到底把哪些文件拼进了 system prompt？
 *
 * <p>实验设计：给 alice 和 bob 各放一份内容不同的 MEMORY.md（用户级长期记忆），
 * 工作区放 AGENTS.md（共享人格），alice 的 memory/ 目录放一份每日记忆文件。
 * fake model 记录每次请求的 SYSTEM 消息，验证注入规则。
 */
class MemoryInjectionTest {

    @TempDir Path tempDir;

    @Test
    void eachUserGetsOwnMemoryAndSharedAgentsMd() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(
                workspace.resolve("AGENTS.md"),
                "# note-taker\n你是一个帮助用户做笔记的助手。AGENTS-MD-SHARED-MARKER\n");
        Files.createDirectories(workspace.resolve("alice").resolve("memory"));
        Files.writeString(
                workspace.resolve("alice").resolve("MEMORY.md"), "# alice 的长期记忆\n- 最爱喝拿铁\n");
        Files.writeString(
                workspace.resolve("alice").resolve("memory").resolve("2026-01-01.md"),
                "# 每日记忆原料\n- DAILY-RAW-MARKER 不应自动注入\n");
        Files.createDirectories(workspace.resolve("bob"));
        Files.writeString(
                workspace.resolve("bob").resolve("MEMORY.md"), "# bob 的长期记忆\n- 最爱喝美式\n");

        RecordingModel model = new RecordingModel();
        try (var agent =
                HarnessAgent.builder()
                        .name("note-taker")
                        .sysPrompt("你是一个帮助用户做笔记的助手。")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .build()) {
            agent.call(
                            new UserMessage("嗨"),
                            RuntimeContext.builder().userId("alice").sessionId("s1").build())
                    .block();
            agent.call(
                            new UserMessage("嗨"),
                            RuntimeContext.builder().userId("bob").sessionId("s2").build())
                    .block();
        }

        String aliceSystem = systemText(model, 0);
        String bobSystem = systemText(model, 1);

        // 1. 共享人格：两个用户都能看到工作区级 AGENTS.md。
        assertTrue(aliceSystem.contains("AGENTS-MD-SHARED-MARKER"), "AGENTS.md 应注入所有用户");
        assertTrue(bobSystem.contains("AGENTS-MD-SHARED-MARKER"));

        // 2. 用户级长期记忆：各看各的 MEMORY.md，互不串。
        assertTrue(aliceSystem.contains("拿铁"), "alice 应看到自己的 MEMORY.md");
        assertFalse(aliceSystem.contains("美式"), "alice 不应看到 bob 的记忆");
        assertTrue(bobSystem.contains("美式"));
        assertFalse(bobSystem.contains("拿铁"));

        // 3. 每日记忆原料文件不自动注入（只有提炼后的 MEMORY.md 进提示词，原料靠工具检索）。
        assertFalse(aliceSystem.contains("DAILY-RAW-MARKER"), "memory/ 每日文件不应自动注入");
    }

    /**
     * 反向实验：MEMORY.md 真的超大时会怎样？
     *
     * <p>结论（本测试固化）：WorkspaceContextMiddleware 注入 AGENTS.md + MEMORY.md +
     * knowledge 时受 maxContextTokens（默认 8000 估算 token，builder.maxContextTokens 可调）
     * 预算约束，超了就 truncateToTokenBudget 保头去尾，并在结尾附
     * "(memory truncated — use memory_search for older entries)" 引导模型走检索路。
     * 所以注入端有运行时兜底，不会把上下文窗口撑爆；维护端还有
     * MemoryConfig.consolidationMaxTokens（默认 4000）平时把 MEMORY.md 压在预算内。
     */
    @Test
    void oversizedMemoryMdIsTruncatedWithSearchHint() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace2"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        Files.createDirectories(workspace.resolve("carol"));
        StringBuilder giant = new StringBuilder("# carol 的长期记忆\nGIANT-MARKER-START\n");
        for (int i = 0; i < 5000; i++) {
            giant.append("aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk llll mmmm\n");
        }
        giant.append("GIANT-MARKER-END\n");
        Files.writeString(workspace.resolve("carol").resolve("MEMORY.md"), giant.toString());

        RecordingModel model = new RecordingModel();
        try (var agent =
                HarnessAgent.builder()
                        .name("note-taker")
                        .sysPrompt("你是一个帮助用户做笔记的助手。")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .build()) {
            agent.call(
                            new UserMessage("嗨"),
                            RuntimeContext.builder().userId("carol").sessionId("s3").build())
                    .block();
        }

        String system = systemText(model, 0);
        assertTrue(system.contains("GIANT-MARKER-START"), "保头：记忆块从开头注入");
        assertFalse(system.contains("GIANT-MARKER-END"), "去尾：超出预算的部分被截断");
        assertTrue(
                system.contains("memory truncated") && system.contains("memory_search"),
                "截断处附带提示：用 memory_search 查更早的记忆");
        assertTrue(system.length() < giant.length(), "SYSTEM 远小于原始文件");
    }

    private static String systemText(RecordingModel model, int callIndex) {
        return model.requests.get(callIndex).stream()
                .filter(m -> m.getRole() == MsgRole.SYSTEM)
                .findFirst()
                .map(Msg::getTextContent)
                .orElse("");
    }

    private static final class RecordingModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("好的。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-memory-injection-model";
        }
    }
}
