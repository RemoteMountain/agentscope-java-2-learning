package learning.agentscope.quickstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Flux;

/**
 * 多用户并发案例的确定性 UT。
 *
 * <p>三个验证角度，全部使用同一个 agent 实例（官网：实例无状态，单例即可）：
 * 1. 隔离——不同用户、同用户不同会话，对话上下文互不可见；
 * 2. 并行——不同 (userId, sessionId) 的两路调用同时在模型里执行（inFlight 达到 2）；
 * 3. 串行——同一 (userId, sessionId) 的两路调用被 SessionTurnGate 排队，inFlight 恒为 1，
 *    且第二轮请求自动带上第一轮的历史。
 */
class MultiUserFirstAgentTest {

    @TempDir Path tempDir;

    @Test
    void singleInstanceIsolatesUsersAndSessions() throws Exception {
        Path workspace = prepareWorkspace();
        RecallModel model = new RecallModel();

        try (var agent = MultiUserFirstAgent.buildAgent(model, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext aliceA =
                    RuntimeContext.builder().userId("alice").sessionId("session-a").build();
            RuntimeContext aliceB =
                    RuntimeContext.builder().userId("alice").sessionId("session-b").build();
            RuntimeContext bobA =
                    RuntimeContext.builder().userId("bob").sessionId("session-a").build();

            // alice 在会话 a 说了名字；bob 在他自己的会话里说话。
            agent.call(new UserMessage("我叫天宇，在准备 ReAct 分享。"), aliceA).block();
            agent.call(new UserMessage("我叫小马，在准备期末考试。"), bobA).block();

            // alice 换一个新会话 b：不记得 session-a 的内容（会话级隔离）。
            Msg aliceNewSession = agent.call(new UserMessage("我叫什么？"), aliceB).block();
            assertEquals("当前会话中没有足够信息。", aliceNewSession.getTextContent());

            // alice 回到会话 a：记得（自动恢复）；bob 在自己的会话 a：只记得自己的。
            Msg aliceRecall = agent.call(new UserMessage("我叫什么？"), aliceA).block();
            assertEquals("你叫天宇，在准备 ReAct 分享。", aliceRecall.getTextContent());
            Msg bobRecall = agent.call(new UserMessage("我叫什么？"), bobA).block();
            assertEquals("你叫小马，在准备期末考试。", bobRecall.getTextContent());

            // bob 的第 5 次调用（下标 4）里绝不能出现 alice 的名字。
            assertFalse(
                    model.requests.get(4).stream()
                            .anyMatch(m -> m.getTextContent().contains("天宇")),
                    "bob 的上下文不应混入 alice 的对话");
        }
    }

    @Test
    void differentSessionsRunInParallelOnOneInstance() throws Exception {
        Path workspace = prepareWorkspace();
        ConcurrencyProbeModel model = new ConcurrencyProbeModel();

        try (var agent = MultiUserFirstAgent.buildAgent(model, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext alice =
                    RuntimeContext.builder().userId("alice").sessionId("parallel-a").build();
            RuntimeContext bob =
                    RuntimeContext.builder().userId("bob").sessionId("parallel-b").build();

            // subscribeOn 模拟两个请求线程；zip 同时发起。
            Mono.zip(
                            agent.call(new UserMessage("我叫天宇。"), alice)
                                    .subscribeOn(Schedulers.boundedElastic()),
                            agent.call(new UserMessage("我叫小马。"), bob)
                                    .subscribeOn(Schedulers.boundedElastic()))
                    .block(Duration.ofSeconds(60));

            assertEquals(2, model.requests.size());
            assertEquals(
                    2,
                    model.maxInFlight.get(),
                    "不同 (userId, sessionId) 的调用应同时进入模型（官网：完全并行）");

            // 产物按用户落盘，互不混目录。
            assertTrue(
                    Files.isDirectory(workspace.resolve("alice").resolve("agents")),
                    "alice 的产物目录存在");
            assertTrue(
                    Files.isDirectory(workspace.resolve("bob").resolve("agents")),
                    "bob 的产物目录存在");
        }
    }

    @Test
    void sameSessionIsSerializedAndSecondTurnSeesHistory() throws Exception {
        Path workspace = prepareWorkspace();
        ConcurrencyProbeModel model = new ConcurrencyProbeModel();

        try (var agent = MultiUserFirstAgent.buildAgent(model, workspace, new InMemoryAgentStateStore())) {
            RuntimeContext sameSession =
                    RuntimeContext.builder().userId("alice").sessionId("same-session").build();

            Mono.zip(
                            agent.call(new UserMessage("我叫天宇。"), sameSession)
                                    .subscribeOn(Schedulers.boundedElastic()),
                            agent.call(new UserMessage("我叫什么？"), sameSession)
                                    .subscribeOn(Schedulers.boundedElastic()))
                    .block(Duration.ofSeconds(60));

            assertEquals(2, model.requests.size());
            assertEquals(
                    1,
                    model.maxInFlight.get(),
                    "同一 (userId, sessionId) 的调用应被 SessionTurnGate 串行化");

            // 串行化的收益：第二轮自动带上第一轮的对话，不会丢状态。
            boolean secondTurnHasHistory =
                    model.requests.get(1).stream()
                            .anyMatch(m -> m.getRole() == MsgRole.USER
                                    && m.getTextContent().contains("我叫天宇"));
            assertTrue(secondTurnHasHistory, "第二轮请求应包含第一轮的历史");
        }
    }

    private Path prepareWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# note-taker\n你是一个帮助用户做笔记的助手。\n");
        return workspace;
    }

    /**
     * 隔离测试用 fake model：按请求里出现过的名字作答，模拟“记得/不记得”。
     */
    private static final class RecallModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            String allText =
                    messages.stream().map(Msg::getTextContent).reduce("", (a, b) -> a + b);
            String answer;
            if (allText.contains("我叫什么") || allText.contains("你知道")) {
                if (allText.contains("天宇") && allText.contains("ReAct")) {
                    answer = "你叫天宇，在准备 ReAct 分享。";
                } else if (allText.contains("小马")) {
                    answer = "你叫小马，在准备期末考试。";
                } else {
                    answer = "当前会话中没有足够信息。";
                }
            } else {
                answer = "好的，记住了。";
            }
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

    /**
     * 并发探针 fake model：sleep 300ms 模拟模型延迟；统计同时在模型里的调用数。
     * 若框架并行，两路调用的 300ms 窗口必然重叠（maxInFlight=2）；
     * 若被串行化，stream() 不会同时进入（maxInFlight=1）。
     */
    private static final class ConcurrencyProbeModel implements Model {
        private final List<List<Msg>> requests = new ArrayList<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("好的。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-concurrency-probe-model";
        }
    }
}
