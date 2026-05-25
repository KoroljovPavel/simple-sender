package com.botfunnel.bot;

import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.SubscriberService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Test-only {@code @Primary} {@link TelegramSender} with a SHORT overall timeout (3s) so the
 * 429-retry-exhaustion path terminates in seconds instead of the production 30s budget. Lives in the
 * {@code com.botfunnel.bot} package to reach the package-private timeout constructor. Imported by
 * {@code SubscriberPersonalMessageIT}; the bean wires the same dependencies as the production sender,
 * with {@code app.telegram.base-url} pointed at the IT's MockWebServer via {@code @DynamicPropertySource}.
 */
@TestConfiguration
public class PersonalMessageSenderTestConfig {

    @Bean
    @Primary
    TelegramSender shortTimeoutTelegramSender(RestClient.Builder builder,
                                              @Value("${app.telegram.base-url}") String baseUrl,
                                              BotRepository botRepository,
                                              TokenEncryptor tokenEncryptor,
                                              EventService eventService,
                                              SubscriberService subscriberService) {
        return new TelegramSender(builder, baseUrl,
                TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, Duration.ofSeconds(3),
                botRepository, tokenEncryptor, eventService, subscriberService);
    }
}
