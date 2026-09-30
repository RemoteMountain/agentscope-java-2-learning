package learning.agentscope.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * AgentScope Java 2.0 工具执行上下文案例。
 *
 * <p>本案例专门演示工具如何读取每次调用携带的 {@link RuntimeContext}：
 * <ul>
 *   <li>注解式 {@link Tool} 方法通过未标注 {@link ToolParam} 的参数自动注入类型化 POJO；</li>
 *   <li>继承 {@link ToolBase} 的工具通过 {@link ToolCallParam#getRuntimeContext()} 显式读取上下文。</li>
 * </ul>
 * 注入参数不会进入模型可见的工具 Schema，因此模型只能提供真正的业务参数。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html（接收 Context）
 */
public final class ToolExecutionContextExample {

    /** 每次调用传入的用户信息，工具可直接按类型获取。 */
    public record UserContext(String username, String locale, List<String> preferences) {}

    /** 给 ToolBase 工具使用的审计信息。 */
    public record AuditContext(String requestId) {}

    private ToolExecutionContextExample() {}

    /** 注册注解式工具和 ToolBase 工具。 */
    public static Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PersonalizedTools());
        toolkit.registerAgentTool(new AuditNoteTool());
        return toolkit;
    }

    /** 官网模型配置：每次 call 通过 RuntimeContext 携带用户信息。 */
    public static ReActAgent buildAgent() {
        return ReActAgent.builder()
                .name("tool-context-demo")
                .sysPrompt("你是一个个性化助手。需要问候或读取偏好时必须调用工具。")
                .model("dashscope:qwen-plus")
                .toolkit(buildToolkit())
                .maxIters(5)
                .build();
    }

    /** 为确定性 UT 提供可替换模型的构造入口。 */
    static ReActAgent buildAgent(Model model) {
        return ReActAgent.builder()
                .name("tool-context-demo")
                .sysPrompt("你是一个个性化助手。需要问候或读取偏好时必须调用工具。")
                .model(model)
                .toolkit(buildToolkit())
                .maxIters(5)
                .build();
    }

    public static void main(String[] args) {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println(
                    "请先设置环境变量 DASHSCOPE_API_KEY，再运行 ToolExecutionContextExample。");
            return;
        }

        try (ReActAgent agent = buildAgent()) {
            RuntimeContext aliceContext = RuntimeContext.builder()
                    .userId("alice")
                    .sessionId("tool-context-alice")
                    .put(UserContext.class,
                            new UserContext("alice", "zh", List.of("紧凑视图", "深色模式")))
                    .put(AuditContext.class, new AuditContext("req-alice-001"))
                    .build();

            Msg result = agent.call(
                            List.of(new UserMessage("问候我，并告诉我有哪些界面偏好。")),
                            aliceContext)
                    .block();
            System.out.println("回答：" + (result == null ? "(null)" : result.getTextContent()));
        }
    }

    /** @Tool 方法中的 UserContext 参数不带 @ToolParam，因此由框架从 RuntimeContext 注入。 */
    public static final class PersonalizedTools {

        @Tool(name = "greet_user", description = "使用当前用户的姓名进行问候。", readOnly = true,
                concurrencySafe = true)
        public String greet(
                @ToolParam(name = "greeting", description = "问候语，例如你好或 Hello。") String greeting,
                UserContext userContext) {
            String username = userContext == null ? "unknown" : userContext.username();
            return greeting + "，" + username + "！";
        }

        @Tool(name = "get_preferences", description = "读取当前用户的界面偏好。", readOnly = true,
                concurrencySafe = true)
        public String getPreferences(UserContext userContext) {
            if (userContext == null) {
                return "没有可用的用户上下文";
            }
            return "user=" + userContext.username()
                    + ", locale=" + userContext.locale()
                    + ", preferences=" + String.join(", ", userContext.preferences());
        }
    }

    /**
     * ToolBase 路径：模型只提供 note，RuntimeContext 由 ToolCallParam 携带进来。
     */
    public static final class AuditNoteTool extends ToolBase {

        public AuditNoteTool() {
            super(ToolBase.builder()
                    .name("audit_note")
                    .description("记录一条带当前用户和请求号的审计信息。")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "note", Map.of("type", "string", "description", "审计内容。")),
                            "required", List.of("note")))
                    .readOnly(true)
                    .concurrencySafe(true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            RuntimeContext runtimeContext = param.getRuntimeContext();
            AuditContext audit = runtimeContext == null ? null : runtimeContext.get(AuditContext.class);
            String userId = runtimeContext == null ? null : runtimeContext.getUserId();
            String note = String.valueOf(param.getInput().get("note"));
            String result = "user=" + userId
                    + ", request=" + (audit == null ? null : audit.requestId())
                    + ", note=" + note;

            List<ContentBlock> output = List.of(TextBlock.builder().text(result).build());
            return Mono.just(ToolResultBlock.builder()
                    .id(param.getToolUseBlock().getId())
                    .name(getName())
                    .output(output)
                    .build());
        }
    }
}
