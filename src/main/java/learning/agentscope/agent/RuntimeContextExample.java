package learning.agentscope.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

/**
 * AgentScope Java 2.0：RuntimeContext——每次 call 的"叫号单"。
 *
 * <p>官网定位（building-blocks/context）：RuntimeContext 是随每次 {@code agent.call(msgs, ctx)}
 * 携带的轻量上下文，钩子和工具在一次调用期间共享它。三样东西放上面：
 *
 * <p>1. 内建字段 {@code userId} / {@code sessionId}——决定加载哪个 AgentState 槽位（state 寻址键）；
 * <p>2. 字符串属性 {@code put("k", v)}——request_id 这类随请求走的杂项；
 * <p>3. 类型化属性 {@code put(MyClass.class, obj)}——强类型单例，工具可直接注入。
 *
 * <p>两个关键事实：属性<b>不持久化</b>（state 持久化，但 context 属性随调用结束即弃）；
 * 工具方法里<b>不带 {@code @ToolParam} 的参数由框架注入</b>（如 RuntimeContext、自定义 POJO），
 * 不会出现在给模型看的 schema 里。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/en/docs/building-blocks/context.html
 * https://java.agentscope.io/v2/en/docs/building-blocks/tool.html（参数注入规则）
 */
public final class RuntimeContextExample {

    /** 演示类型化属性的载体：租户信息。 */
    public record TenantInfo(String tenantId, String region) {}

    /**
     * 两个工具验证三种注入路径：
     * echo_meta —— 注入 RuntimeContext，在方法体里取字符串属性和类型化属性；
     * echo_tenant —— 直接注入 TenantInfo POJO（参数不带 @ToolParam，模型看不见也填不了）。
     */
    public static final class MetaTools {

        @Tool(
                name = "echo_meta",
                description = "记录笔记并回显当前请求的元数据（用户、请求号、租户）。",
                readOnly = true,
                concurrencySafe = true)
        public String echoMeta(
                @ToolParam(name = "content", description = "笔记内容") String content,
                RuntimeContext context) {
            TenantInfo tenant = context.get(TenantInfo.class);
            return "user=" + context.getUserId()
                    + ", session=" + context.getSessionId()
                    + ", request_id=" + context.get("request_id")
                    + ", tenant=" + (tenant == null ? "无" : tenant.tenantId())
                    + ", content=" + content;
        }

        @Tool(
                name = "echo_tenant",
                description = "回显当前租户信息。",
                readOnly = true,
                concurrencySafe = true)
        public String echoTenant(
                @ToolParam(name = "content", description = "任意内容") String content,
                TenantInfo tenant) {
            return "tenant=" + (tenant == null ? "无" : tenant.tenantId() + "@" + tenant.region())
                    + ", content=" + content;
        }
    }

    /**
     * 官网示例形态：builder 链上直接 put 字符串属性和类型化属性。
     * 运行 main 前设置 DASHSCOPE_API_KEY。
     */
    public static ReActAgent buildAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MetaTools());
        return ReActAgent.builder()
                .name("ctx-demo")
                .sysPrompt("你是一个笔记助手，记录前先调用工具。")
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .build();
    }

    static ReActAgent buildAgent(io.agentscope.core.model.Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MetaTools());
        return ReActAgent.builder()
                .name("ctx-demo")
                .sysPrompt("你是一个笔记助手，记录前先调用工具。")
                .model(model)
                .toolkit(toolkit)
                .build();
    }

    public static void main(String[] args) {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 RuntimeContextExample。");
            return;
        }

        try (ReActAgent agent = buildAgent()) {
            RuntimeContext context = RuntimeContext.builder()
                    .userId("alice")
                    .sessionId("ctx-demo")
                    .put("request_id", "req-2026-09-23-001")
                    .put(TenantInfo.class, new TenantInfo("tenant-7", "cn-east"))
                    .build();

            Msg result = agent
                    .call(List.of(new UserMessage("记一条笔记：今天学习了 RuntimeContext 的三种注入方式。")),
                            context)
                    .block();
            System.out.println("回答：" + result.getTextContent());
            System.out.println("（工具返回的元数据里应有 alice / req-2026-09-23-001 / tenant-7）");
        }
    }
}
