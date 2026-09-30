package learning.agentscope.tool;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * AgentScope Java 2.0 工具案例：注解式 Tool 与 Toolkit。
 *
 * <p>本案例从一个普通 Java 类开始，用 {@link Tool} 描述模型可调用的能力，用
 * {@link ToolParam} 描述模型提供的参数，再通过 {@link Toolkit#registerTool(Object)} 注册。
 * ReActAgent 会在模型返回 ToolUseBlock 后执行工具，把成功结果或错误结果放回下一轮模型请求。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html
 */
public final class ToolExample {

    public static final String ADD_TOOL = "add_numbers";
    public static final String DIVIDE_TOOL = "divide_numbers";

    private ToolExample() {}

    /** 创建本案例使用的 Toolkit，便于 main 和测试复用同一注册方式。 */
    public static Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new CalculatorTools());
        return toolkit;
    }

    /** 官网模型配置：ModelRegistry 会根据字符串 id 创建 DashScope 模型。 */
    public static ReActAgent buildAgent() {
        return ReActAgent.builder()
                .name("tool-demo")
                .sysPrompt("你是一个严谨的计算助手。涉及计算时必须调用对应工具，不要心算。")
                .model("dashscope:qwen-plus")
                .toolkit(buildToolkit())
                .maxIters(5)
                .build();
    }

    /** 为确定性 UT 提供可替换模型的构造入口。 */
    static ReActAgent buildAgent(Model model) {
        return ReActAgent.builder()
                .name("tool-demo")
                .sysPrompt("你是一个严谨的计算助手。涉及计算时必须调用对应工具，不要心算。")
                .model(model)
                .toolkit(buildToolkit())
                .maxIters(5)
                .build();
    }

    public static void main(String[] args) {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 ToolExample。");
            return;
        }

        try (ReActAgent agent = buildAgent()) {
            Msg result = agent.call(new UserMessage("请用工具计算 12 加 30，再用一句话告诉我结果。"))
                    .block();
            System.out.println(result.getTextContent());
        }
    }

    /** 两个普通 Java 方法：注解负责暴露工具名、描述和参数 Schema。 */
    public static final class CalculatorTools {

        @Tool(
                name = ADD_TOOL,
                description = "两个整数相加，返回和。",
                readOnly = true,
                concurrencySafe = true)
        public int addNumbers(
                @ToolParam(name = "left", description = "左侧整数。") int left,
                @ToolParam(name = "right", description = "右侧整数。") int right) {
            return left + right;
        }

        @Tool(
                name = DIVIDE_TOOL,
                description = "两个整数相除，返回商。除数不能为零。",
                readOnly = true,
                concurrencySafe = true)
        public int divideNumbers(
                @ToolParam(name = "dividend", description = "被除数。") int dividend,
                @ToolParam(name = "divisor", description = "除数，不能为零。") int divisor) {
            if (divisor == 0) {
                throw new IllegalArgumentException("除数不能为 0");
            }
            return dividend / divisor;
        }
    }
}
