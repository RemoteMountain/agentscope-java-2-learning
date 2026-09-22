package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
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
 * 对照实验：disableMemoryHooks() 到底关掉了什么。
 *
 * <p>左边（默认开启记忆钩子）：每轮回答结束后，MemoryFlushMiddleware 会异步追加一次
 * 模型调用（提示词以 "Extract NEW memories..." 开头；当天已有台账时是
 * "Today's daily ledger..."），把这一轮提炼成记忆笔记写入 workspace/<userId>/memory/。
 *
 * <p>右边（disableMemoryHooks）：每轮恰好一次模型调用，不产生任何 memory 文件；
 * 会话内记忆（state store）不受影响——那是另一套系统。
 */
class MemoryHooksDifferenceTest {

    @TempDir Path tempDir;

    @Test
    void defaultHooksFlushMemoryAfterTurn() throws Exception {
        Path workspace = prepareWorkspace("hooks-on");
        CountingModel model = new CountingModel();

        try (var agent = buildAgent(model, workspace, false)) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("hooks-on").build();
            agent.call(new UserMessage("我叫天宇，今天准备 ReAct 分享。"), context).block();

            // 回答本身 1 次调用；记忆 flush 是异步的，等它到来。
            await("记忆 flush 模型调用", () -> model.requests.size() >= 2);
            assertTrue(
                    model.requests.get(1).stream()
                            .anyMatch(m -> m.getTextContent().startsWith("Extract NEW memories")),
                    "flush 调用的提示词特征：Extract NEW memories...");

            // 记忆笔记落盘到 workspace/alice/memory/<当日>.md。
            Path memoryDir = workspace.resolve("alice").resolve("memory");
            await("记忆文件写入 workspace", () -> anyFileIn(memoryDir));
        }
    }

    @Test
    void disabledHooksExactlyOneCallAndNoMemoryFiles() throws Exception {
        Path workspace = prepareWorkspace("hooks-off");
        CountingModel model = new CountingModel();

        try (var agent = buildAgent(model, workspace, true)) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("hooks-off").build();
            agent.call(new UserMessage("我叫天宇，今天准备 ReAct 分享。"), context).block();

            // 留出时间证明“异步 flush 确实不存在”，而不是还没到。
            Thread.sleep(500);
            assertEquals(1, model.requests.size(), "只有本轮回答这一次模型调用");
            assertFalse(
                    Files.exists(workspace.resolve("alice").resolve("memory")),
                    "不产生 memory 目录");
        }
    }

    private Path prepareWorkspace(String name) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        return workspace;
    }

    private static HarnessAgent buildAgent(Model model, Path workspace, boolean disableHooks) {
        var builder =
                HarnessAgent.builder()
                        .name("note-taker")
                        .sysPrompt("你是一个帮助用户做笔记的助手。")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore());
        if (disableHooks) {
            builder.disableMemoryHooks();
        }
        return builder.build();
    }

    private static boolean anyFileIn(Path dir) throws Exception {
        return Files.isDirectory(dir)
                && Files.list(dir).filter(Files::isRegularFile).findAny().isPresent();
    }

    /** 轮询等待异步结果，最多 5 秒，避免对框架内部时序做假设。 */
    private static void await(String what, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.check()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待超时：" + what);
    }

    @FunctionalInterface
    private interface Condition {
        boolean check() throws Exception;
    }

    /** 只计数和记录请求的 fake model。 */
    private static final class CountingModel implements Model {
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
            return "fake-memory-hooks-model";
        }
    }
}
