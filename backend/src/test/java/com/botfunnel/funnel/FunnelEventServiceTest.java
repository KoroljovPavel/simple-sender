package com.botfunnel.funnel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for the fan-out dispatcher + the three loop backstops. Execution creation is
 * mocked at the {@link FunnelExecutionFactory} boundary, so fan-out is asserted by counting
 * {@code insertExecution(..., enrollDepth)} invocations.
 */
@ExtendWith(MockitoExtension.class)
class FunnelEventServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String SUBSCRIBER_ID = "sub-1";
    private static final Long TELEGRAM_BOT_ID = 778899L;
    private static final int MAX_FANOUT = 50;
    private static final int RATE_PER_MIN = 20;
    private static final int MAX_DEPTH = 10;

    @Mock BotRepository botRepository;
    @Mock SubscriberService subscriberService;
    @Mock FunnelRepository funnelRepository;
    @Mock FunnelExecutionFactory executionFactory;
    @Mock StringRedisTemplate redisTemplate;

    private FunnelEventService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new FunnelEventService(botRepository, subscriberService, funnelRepository,
                executionFactory, redisTemplate, MAX_FANOUT, RATE_PER_MIN, MAX_DEPTH);
        serviceLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
    }

    // ─── happy path / fan-out ───────────────────────────────────────────────

    @Test
    void dispatch_fansOutToAllMatchingActiveFunnels() {
        stubBotAndSubscriber();
        stubEventFunnels(funnel("f1"), funnel("f2"), funnel("f3"));

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "purchase", 0);

        ArgumentCaptor<Integer> depth = ArgumentCaptor.forClass(Integer.class);
        verify(executionFactory, times(3))
                .insertExecution(eq(PROJECT_ID), any(Funnel.class), eq(SUBSCRIBER_ID),
                        eq(TELEGRAM_BOT_ID), depth.capture());
        assertThat(depth.getAllValues()).containsExactly(0, 0, 0);
    }

    @Test
    void dispatch_stampsOriginDepthOnEachExecution() {
        stubBotAndSubscriber();
        stubRedisIncrement(1L); // depth>0 consults Redis; under limit
        stubEventFunnels(funnel("f1"), funnel("f2"));

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "purchase", 3);

        ArgumentCaptor<Integer> depth = ArgumentCaptor.forClass(Integer.class);
        verify(executionFactory, times(2))
                .insertExecution(any(), any(), any(), any(), depth.capture());
        assertThat(depth.getAllValues()).containsExactly(3, 3);
    }

    @Test
    void dispatch_noMatchingFunnel_isNoOp() {
        stubBotAndSubscriber();
        stubEventFunnels(); // empty

        assertThatCode(() -> service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0))
                .doesNotThrowAnyException();

        verifyNoInteractions(executionFactory);
        assertThat(warnOrInfo(FunnelEventService.LOG_DISPATCH_NO_MATCH)).isTrue();
    }

    @Test
    void dispatch_missingSubscriber_warnsAndNoOps() {
        stubConnectedBot();
        when(subscriberService.findById(PROJECT_ID, SUBSCRIBER_ID)).thenReturn(Optional.empty());

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0);

        verifyNoInteractions(executionFactory);
        assertThat(warn(FunnelEventService.LOG_DISPATCH_NO_SUBSCRIBER)).isTrue();
    }

    @Test
    void dispatch_noConnectedBot_warnsAndNoOps() {
        when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Optional.empty());

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0);

        verifyNoInteractions(executionFactory);
        verifyNoInteractions(funnelRepository);
        assertThat(warn(FunnelEventService.LOG_DISPATCH_NO_BOT)).isTrue();
    }

    @Test
    void dispatch_swallowsThrowable() {
        stubBotAndSubscriber();
        stubEventFunnels(funnel("f1"));
        doThrow(new RuntimeException("boom")).when(executionFactory)
                .insertExecution(any(), any(), any(), any(), anyInt());

        assertThatCode(() -> service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0))
                .doesNotThrowAnyException();

        assertThat(warn(FunnelEventService.LOG_DISPATCH_ERROR)).isTrue();
    }

    // ─── keyword matching ───────────────────────────────────────────────────

    @Test
    void keyword_matches_containsCaseInsensitiveAnyOfMany() {
        stubBotAndSubscriber();
        Funnel hit = funnel("hit");
        hit.setKeywords(List.of("bonus", "sale"));
        Funnel miss = funnel("miss");
        miss.setKeywords(List.of("discount"));
        when(funnelRepository.findByProjectIdAndTriggerTypeAndStatus(PROJECT_ID, "keyword", FunnelStatus.active))
                .thenReturn(new ArrayList<>(List.of(hit, miss)));

        // "Get your SALE now" contains "sale" (case-insensitive) → matches `hit` only.
        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "keyword", "Get your SALE now", 0);

        verify(executionFactory, times(1))
                .insertExecution(eq(PROJECT_ID), eq(hit), eq(SUBSCRIBER_ID), eq(TELEGRAM_BOT_ID), eq(0));
        verify(executionFactory, never())
                .insertExecution(any(), eq(miss), any(), any(), anyInt());
    }

    @Test
    void keyword_noKeywordInText_isNoOp() {
        stubBotAndSubscriber();
        Funnel f = funnel("f");
        f.setKeywords(List.of("bonus"));
        when(funnelRepository.findByProjectIdAndTriggerTypeAndStatus(PROJECT_ID, "keyword", FunnelStatus.active))
                .thenReturn(new ArrayList<>(List.of(f)));

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "keyword", "nothing here", 0);

        verifyNoInteractions(executionFactory);
    }

    // ─── re-enter guard interplay (per-funnel) ──────────────────────────────

    @Test
    void reEnter_duplicateOnOneFunnel_doesNotAbortOtherMatches() {
        stubBotAndSubscriber();
        Funnel dup = funnel("dup");
        Funnel ok = funnel("ok");
        stubEventFunnels(dup, ok);
        // The first funnel's insert collides with the re-enter guard; the second must still insert.
        doThrow(new DuplicateKeyException("re-enter")).when(executionFactory)
                .insertExecution(eq(PROJECT_ID), eq(dup), any(), any(), anyInt());

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0);

        verify(executionFactory, times(1))
                .insertExecution(eq(PROJECT_ID), eq(ok), eq(SUBSCRIBER_ID), eq(TELEGRAM_BOT_ID), eq(0));
        assertThat(warnOrInfo(FunnelEventService.LOG_DISPATCH_REENTER_IGNORED)).isTrue();
    }

    @Test
    void reEnter_allowReEnterFunnel_cancelsExistingThenInserts() {
        stubBotAndSubscriber();
        Funnel restart = funnel("restart");
        restart.setAllowReEnter(true);
        stubEventFunnels(restart);

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0);

        InOrder inOrder = inOrder(executionFactory);
        inOrder.verify(executionFactory).cancelExistingForPair(PROJECT_ID, "restart", SUBSCRIBER_ID);
        inOrder.verify(executionFactory)
                .insertExecution(eq(PROJECT_ID), eq(restart), eq(SUBSCRIBER_ID), eq(TELEGRAM_BOT_ID), eq(0));
    }

    // ─── backstop (a) volume ────────────────────────────────────────────────

    @Test
    void volumeLimit_countsOnlyDepthGreaterThanZero() {
        stubBotAndSubscriber();
        stubEventFunnels(funnel("f1"));

        // depth==0 (human/external root): Redis is NEVER consulted, never dropped.
        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0);

        verifyNoInteractions(redisTemplate);
        verify(executionFactory, times(1)).insertExecution(any(), any(), any(), any(), eq(0));
    }

    @Test
    void volumeLimit_depthGreaterThanZeroOverThreshold_isDroppedAndWarns() {
        stubConnectedBot();
        stubSubscriber();
        stubRedisIncrement(RATE_PER_MIN + 1L); // over the 20/min limit

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 5);

        verifyNoInteractions(executionFactory);
        verifyNoInteractions(funnelRepository); // dropped before any funnel lookup
        assertThat(warn(FunnelEventService.LOG_DROP_VOLUME)).isTrue();
    }

    @Test
    void volumeLimit_redisDown_failsOpen() {
        stubBotAndSubscriber();
        stubEventFunnels(funnel("f1"));
        ValueOperations<String, String> ops = mock();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 4);

        // Fail-open: dispatch proceeds despite the Redis fault.
        verify(executionFactory, times(1)).insertExecution(any(), any(), any(), any(), eq(4));
        assertThat(warn(FunnelEventService.LOG_AUTO_ENROLL_RATE_REDIS_FAIL_OPEN)).isTrue();
    }

    // ─── backstop (b) depth cap ─────────────────────────────────────────────

    @Test
    void depthCap_dropsAboveCap_evenWhenRedisDown() {
        // No bot/subscriber/funnel stubs needed: the depth cap gates BEFORE any of them. Redis is never
        // consulted either, so the cap holds with Redis unavailable.
        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", MAX_DEPTH + 1);

        verifyNoInteractions(executionFactory);
        verifyNoInteractions(redisTemplate);
        verifyNoInteractions(botRepository);
        assertThat(warn(FunnelEventService.LOG_DROP_DEPTH_CAP)).isTrue();
    }

    @Test
    void depthCap_atCap_isAllowed() {
        stubBotAndSubscriber();
        stubRedisIncrement(1L);
        stubEventFunnels(funnel("f1"));

        service.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", MAX_DEPTH);

        verify(executionFactory, times(1)).insertExecution(any(), any(), any(), any(), eq(MAX_DEPTH));
    }

    // ─── backstop (c) fan-out ceiling ───────────────────────────────────────

    @Test
    void fanoutCeiling_insertsAtMostN_evenWhenRedisDown() {
        FunnelEventService capped = new FunnelEventService(botRepository, subscriberService,
                funnelRepository, executionFactory, redisTemplate, 2, RATE_PER_MIN, MAX_DEPTH);
        stubBotAndSubscriber();
        stubEventFunnels(funnel("f1"), funnel("f2"), funnel("f3"), funnel("f4"));

        // depth 0 → Redis never consulted (ceiling is Redis-independent).
        capped.dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID, "event", "x", 0);

        verify(executionFactory, times(2)).insertExecution(any(), any(), any(), any(), eq(0));
        verifyNoInteractions(redisTemplate);
        assertThat(warn(FunnelEventService.LOG_DROP_FANOUT)).isTrue();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private Funnel funnel(String id) {
        Funnel f = new Funnel();
        f.setId(id);
        f.setProjectId(PROJECT_ID);
        f.setStatus(FunnelStatus.active);
        f.setAllowReEnter(false);
        return f;
    }

    private void stubConnectedBot() {
        Bot bot = new Bot();
        bot.setProjectId(PROJECT_ID);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setStatus(BotStatus.CONNECTED);
        lenient().when(botRepository.findByProjectIdAndStatus(PROJECT_ID, BotStatus.CONNECTED))
                .thenReturn(Optional.of(bot));
    }

    private void stubSubscriber() {
        Subscriber s = new Subscriber();
        s.setId(SUBSCRIBER_ID);
        s.setProjectId(PROJECT_ID);
        lenient().when(subscriberService.findById(PROJECT_ID, SUBSCRIBER_ID)).thenReturn(Optional.of(s));
    }

    private void stubBotAndSubscriber() {
        stubConnectedBot();
        stubSubscriber();
    }

    private void stubEventFunnels(Funnel... funnels) {
        when(funnelRepository.findAllByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                eq(PROJECT_ID), eq("event"), anyString(), eq(FunnelStatus.active)))
                .thenReturn(new ArrayList<>(List.of(funnels)));
    }

    private void stubRedisIncrement(long count) {
        ValueOperations<String, String> ops = mock();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(count);
        lenient().when(redisTemplate.expire(anyString(), any())).thenReturn(true);
    }

    private boolean warn(String marker) {
        return appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(marker));
    }

    private boolean warnOrInfo(String marker) {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(marker));
    }
}
