package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
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
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 回答一个问题：模型用 write_file 写“相对路径”文件时，落在哪个目录？由什么代码控制？
 *
 * <p>结论（本测试固化）：文件工具的相对路径经 WorkspaceManager.resolveRuntimeDataPath 解析，
 * 默认 NamespaceFactory 按 RuntimeContext 返回 [userId] 命名空间段，所以 hello.md 落在
 * workspace/&lt;userId&gt;/hello.md——每个用户一个独立目录，不会碰工作区根目录的共享文件
 * （AGENTS.md、knowledge/ 等）。
 *
 * <p>推论：真实运行产物里的 alice/workspace/ 子目录不是框架固定目录，而是模型自己调用
 * write_file 时把路径写成了 "workspace/react_talk_outline.md"——"workspace" 这层名字是模型起的。
 */
class UserWorkspaceDirTest {

    @TempDir Path tempDir;

    @Test
    void relativeWriteFileLandsInPerUserDirectory() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        WriteFileModel model = new WriteFileModel();

        try (var agent =
                HarnessAgent.builder()
                        .name("note-taker")
                        .sysPrompt("你是一个帮助用户做笔记的助手。")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(new InMemoryAgentStateStore())
                        .disableMemoryHooks()
                        .build()) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("dir-test").build();
            agent.call(new UserMessage("把这句话记到 hello.md：你好"), context).block();
        }

        // 相对路径 hello.md 落在 alice/ 目录下，而不是工作区根目录，也不是 alice/workspace/。
        Path userFile = workspace.resolve("alice").resolve("hello.md");
        assertTrue(Files.exists(userFile), "write_file 的相对路径应落在 <userId>/ 下");
        assertEquals("你好", Files.readString(userFile).trim());
        assertTrue(
                model.requests.get(1).stream().anyMatch(m -> m.getRole() == MsgRole.TOOL),
                "第二次模型请求应包含工具结果");
    }

    /**
     * 第一次调用 write_file 写相对路径文件；第二次收尾。
     *
     * <p>坑位记录：ToolUseBlock 必须同时给 input Map 和 content JSON 字符串（五参构造器），
     * 框架做参数校验时读的是 content 里的 JSON；只给 input Map 会报“未找到所需属性 path”。
     */
    private static final class WriteFileModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();
        private int calls;

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            calls++;
            if (calls == 1) {
                String argsJson = "{\"path\":\"hello.md\",\"content\":\"你好\"}";
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(
                                                new ToolUseBlock(
                                                        "call-1",
                                                        "write_file",
                                                        Map.of(
                                                                "path", "hello.md",
                                                                "content", "你好"),
                                                        argsJson,
                                                        Map.of())))
                                .finishReason("tool_calls")
                                .build());
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("已保存。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-write-file-model";
        }
    }
}
