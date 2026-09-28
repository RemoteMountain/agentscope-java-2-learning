package learning.agentscope.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope Java 2.0：中断（interrupt）。
 *
 * <p>官网 building-blocks 一族 {@code interrupt()} 方法（ReActAgent 上有多个重载：
 * 无参、带 Msg、带 RuntimeContext、带 (userId, sessionId)）用于打断正在运行中的调用——
 * 典型场景：用户等不及了点"停止"，或上游超时想放弃这轮。
 *
 * <p>对应官网：
 * https://java.agentscope.io/v2/en/docs/building-blocks/agent.html
 */
public final class InterruptExample {

    private InterruptExample() {}

    /** main 用的 agent：真实模型。 */
    public static ReActAgent buildAgent() {
        return ReActAgent.builder()
                .name("interrupt-demo")
                .sysPrompt("你是一个乐于长篇大论的助手。")
                .model("dashscope:qwen-plus")
                .build();
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("DASHSCOPE_API_KEY") == null
                || System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY，再运行 InterruptExample。");
            return;
        }

        try (ReActAgent agent = buildAgent()) {
            RuntimeContext context =
                    RuntimeContext.builder().userId("alice").sessionId("interrupt-demo").build();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>();

            // 异步发起一个"长问题"，1.5 秒后主动打断——模拟用户点"停止"。
            agent.streamEvents(new UserMessage("请详细论述 AgentScope 的全部设计细节，越长越好。"), context)
                    .collectList()
                    .subscribe(
                            events -> {
                                outcome.set("正常完成，共 " + events.size() + " 个事件");
                                done.countDown();
                            },
                            error -> {
                                outcome.set("以错误结束：" + error.getClass().getSimpleName());
                                done.countDown();
                            });

            Thread.sleep(1500);
            agent.interrupt(context);
            System.out.println("已调用 interrupt()，等待调用收尾……");
            done.await(10, TimeUnit.SECONDS);
            System.out.println("结果：" + outcome.get());
        }
    }

    /** 测试用慢模型：每次调用睡够 delayMillis，模拟慢推理。 */
    public static final class SlowModel implements Model {
        private final long delayMillis;

        public SlowModel(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return reactor.core.publisher.Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("慢工出细活。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-slow-model";
        }
    }
}
