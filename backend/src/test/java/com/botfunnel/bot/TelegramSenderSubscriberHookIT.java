package com.botfunnel.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.SubscriberService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Decision 4 / Task 6: terminal 403 / 400-chat-not-found drive the subscriber state machine. Same
// MockWebServer + handcrafted TelegramSender pattern as TelegramSenderTest (NOT a full Spring slice
// — keeps it fast and tightly scoped). The audit-before-mark ordering is load-bearing and verified
// with InOrder(eventService, subscriberService).
class TelegramSenderSubscriberHookIT {

    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789";
    private static final String BOT_ID = "bot-hook-it-1";
    private static final String OWNER_ID = "owner-hook-it-1";
    private static final String PROJECT_ID = "project-hook-it-1";
    private static final Long TELEGRAM_BOT_ID = 555000111L;
    private static final Long CHAT_ID = 424242L;
    private static final String TEXT = "Hook IT message";
    private static final String IMAGE_URL = "https://example.com/hook.png";
    private static final String CAPTION = "Hook IT caption";
    private static final String HEX_KEY_64 = "0".repeat(64);

    private MockWebServer mockServer;
    private TokenEncryptor encryptor;
    private BotRepository botRepository;
    private EventService eventService;
    private SubscriberService subscriberService;
    private TelegramSender sender;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;
    private String ivB64;
    private String ctB64;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        encryptor = new TokenEncryptor(HEX_KEY_64);
        EncryptedValue ev = encryptor.encrypt(TOKEN);
        ivB64 = Base64.getEncoder().encodeToString(ev.iv());
        ctB64 = Base64.getEncoder().encodeToString(ev.ciphertext());

        botRepository = mock(BotRepository.class);
        eventService = mock(EventService.class);
        subscriberService = mock(SubscriberService.class);
        sender = new TelegramSender(RestClient.builder(), mockServer.url("/").toString(),
                TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, Duration.ofSeconds(30),
                botRepository, encryptor, eventService, subscriberService);
        when(botRepository.findById(BOT_ID)).thenReturn(Optional.of(connectedBot()));

        logger = (Logger) LoggerFactory.getLogger(TelegramSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        try {
            mockServer.shutdown();
        } catch (Exception ignored) {
            // ignored
        }
    }

    @Test
    void http403MarksBlocked() {
        mockServer.enqueue(jsonResponse(403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        // Audit MUST fire before the CRM flip (the audit is the durable record).
        InOrder ordered = inOrder(eventService, subscriberService);
        ordered.verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
        ordered.verify(subscriberService).markBlockedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);

        verify(subscriberService, times(1)).markBlockedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);
        verify(subscriberService, never()).markDeletedByChatId(any(), any(), any());
    }

    @Test
    void http400ChatNotFoundMarksDeleted() {
        mockServer.enqueue(jsonResponse(400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        InOrder ordered = inOrder(eventService, subscriberService);
        ordered.verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
        ordered.verify(subscriberService).markDeletedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);

        verify(subscriberService, never()).markBlockedByChatId(any(), any(), any());
    }

    @Test
    void http400ChatNotFoundCaseInsensitiveMarksDeleted() {
        // Mixed-case description proves the .toLowerCase(Locale.ROOT) match — Telegram casing varies.
        mockServer.enqueue(jsonResponse(400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: CHAT Not Found\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        verify(subscriberService).markDeletedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);
        verify(subscriberService, never()).markBlockedByChatId(any(), any(), any());
    }

    @Test
    void http400NullDescriptionDoesNotMark_noNpe() {
        // No description field → result.description() is null → terminalReasonFor must null-guard
        // and fall through to OTHER (no NPE, no CRM flip).
        mockServer.enqueue(jsonResponse(400, "{\"ok\":false,\"error_code\":400}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
        verify(subscriberService, never()).markBlockedByChatId(any(), any(), any());
        verify(subscriberService, never()).markDeletedByChatId(any(), any(), any());
    }

    @Test
    void http400OtherDescriptionDoesNotMark() {
        mockServer.enqueue(jsonResponse(400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message text is too long\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        // Still audited as a terminal failure...
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
        // ...but no CRM flip for an OTHER-reason 400.
        verify(subscriberService, never()).markBlockedByChatId(any(), any(), any());
        verify(subscriberService, never()).markDeletedByChatId(any(), any(), any());
    }

    @Test
    void http401DoesNotMark() {
        mockServer.enqueue(jsonResponse(401,
                "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(BotTokenInvalidException.class);

        // BotTokenInvalidException is not a TelegramSendException → hook is naturally skipped.
        verifyNoInteractions(subscriberService);
    }

    @Test
    void hookFailureDoesNotSwallowOriginal() {
        mockServer.enqueue(jsonResponse(403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));
        doThrow(new RuntimeException("mongo down"))
                .when(subscriberService).markBlockedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);

        // The downstream failure must NOT replace the original send exception.
        assertThatThrownBy(() -> sender.sendText(BOT_ID, CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        boolean warnLogged = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("TELEGRAM_SENDER_SUBSCRIBER_HOOK_FAILED"));
        assertThat(warnLogged).isTrue();
    }

    @Test
    void sendPhoto_403_marksBlockedByChat() {
        mockServer.enqueue(jsonResponse(403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));

        assertThatThrownBy(() -> sender.sendPhoto(BOT_ID, CHAT_ID, IMAGE_URL, CAPTION, "HTML", OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        InOrder ordered = inOrder(eventService, subscriberService);
        ordered.verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
        ordered.verify(subscriberService).markBlockedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);

        verify(subscriberService, times(1)).markBlockedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);
        verify(subscriberService, never()).markDeletedByChatId(any(), any(), any());
    }

    @Test
    void sendPhoto_chatNotFound_marksDeletedByChat() {
        mockServer.enqueue(jsonResponse(400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"));

        assertThatThrownBy(() -> sender.sendPhoto(BOT_ID, CHAT_ID, IMAGE_URL, CAPTION, "HTML", OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        InOrder ordered = inOrder(eventService, subscriberService);
        ordered.verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
        ordered.verify(subscriberService).markDeletedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);

        verify(subscriberService, times(1)).markDeletedByChatId(PROJECT_ID, TELEGRAM_BOT_ID, CHAT_ID);
        verify(subscriberService, never()).markBlockedByChatId(any(), any(), any());
    }

    private Bot connectedBot() {
        Bot bot = new Bot();
        bot.setId(BOT_ID);
        bot.setProjectId(PROJECT_ID);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setEncryptedTokenIv(ivB64);
        bot.setEncryptedTokenCiphertext(ctB64);
        bot.setStatus(BotStatus.CONNECTED);
        return bot;
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
