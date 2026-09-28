package learning.agentscope.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 中断的确定性 UT。两个实验拼出 interrupt 的真实语义：
 *
 * <p>1. 快速循环场景：模型每次都秒回"再调一次工具"（无限循环），interrupt(context) 在
 *    迭代边界生效——循环被提前掐断，远早于 maxIters=100 的自然结束；
 * <p>2. 阻塞模型场景：单次模型调用睡 3 秒，中途 interrupt 无法硬杀——它是协作式中断
 *    （InterruptControl 置标志位，循环边界检查），不是抢占式线程终止。
 */
class InterruptExampleTest {

    /** 极简工具：供循环模型反复调用。 */
    public static final class PingTool {
        @Tool(name = "ping", description = "回声工具。", readOnly = true, concurrencySafe = true)
        public String ping() {
            return "pong";
        }
    }

    @Test
    void interruptStopsToolLoopAtIterationBoundary() throws Exception {
        LoopModel model = new LoopModel(true); // 异步流式模型，带调度边界
        ReActAgent agent = buildAgent(model, 100);
        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("interrupt-loop").build();

        try (agent) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Object> outcome = new AtomicReference<>();
            long start = System.currentTimeMillis();

            agent.call(List.of(new UserMessage("不停地 ping")), context)
                    .subscribe(
                            msg -> {
                                outcome.set("MSG: " + ((Msg) msg).getTextContent());
                                done.countDown();
                            },
                            err -> {
                                outcome.set("ERROR: " + err.getClass().getSimpleName());
                                done.countDown();
                            });

            Thread.sleep(500);
            agent.interrupt(context);

            assertTrue(done.await(8, TimeUnit.SECONDS), "中断后调用应尽快结束");
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(
                    model.calls.get() < 100,
                    "循环应被提前掐断，实际迭代 " + model.calls.get() + " 次（maxIters=100）");
            System.out.println(
                    "[interrupt 实测] " + elapsed + "ms 结束，迭代 " + model.calls.get()
                            + " 次，结果 = " + outcome.get());
        }
    }

    @Test
    void interruptCannotKillInFlightModelCall() throws Exception {
        ReActAgent agent =
                buildAgent(new InterruptExample.SlowModel(3000), 10);
        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("interrupt-block").build();

        try (agent) {
            CountDownLatch done = new CountDownLatch(1);
            long start = System.currentTimeMillis();
            agent.call(List.of(new UserMessage("慢问题")), context)
                    .subscribe(msg -> done.countDown(), err -> done.countDown());

            Thread.sleep(300);
            agent.interrupt(context);

            assertTrue(done.await(10, TimeUnit.SECONDS));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(
                    elapsed >= 3000,
                    "协作式中断无法硬杀阻塞中的单次模型调用，应等满 3 秒，实际 " + elapsed + "ms");
        }
    }

    private static ReActAgent buildAgent(Model model, int maxIters) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PingTool());
        return ReActAgent.builder()
                .name("interrupt-demo")
                .sysPrompt("你是一个助手。")
                .model(model)
                .toolkit(toolkit)
                // 关键：interrupt 的标志位存在按 (userId, sessionId) 寻址的 AgentState 里，
                // 不挂 stateStore 时调用与 interrupt 可能各拿各的临时实例，标志位传不过去。
                .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                .maxIters(maxIters)
                .build();
    }

    /** 循环模型：每次秒回"再调一次 ping"，永不给最终答复；带 20ms 异步延迟模拟真实流式模型。 */
    private static final class LoopModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean async;

        LoopModel() {
            this(false);
        }

        LoopModel(boolean async) {
            this.async = async;
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int seq = calls.incrementAndGet();
            reactor.core.publisher.Flux<ChatResponse> response =
                    reactor.core.publisher.Flux.just(
                            ChatResponse.builder()
                                    .content(List.of(new ToolUseBlock(
                                            "call-" + seq, "ping", Map.of(), "{}", Map.of())))
                                    .finishReason("tool_calls")
                                    .build());
            return async
                    ? response.delayElements(java.time.Duration.ofMillis(20))
                    : response;
        }

        @Override
        public String getModelName() {
            return "fake-loop-model";
        }
    }
}
