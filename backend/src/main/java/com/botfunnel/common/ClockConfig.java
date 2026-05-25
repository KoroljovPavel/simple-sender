package com.botfunnel.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Registers the application {@link Clock} bean. Injecting a Clock (instead of calling
 * {@code Instant.now()} directly) lets state-machine timestamp assertions run against a fixed clock
 * in unit tests. {@code common} is the canonical home for cross-cutting beans — do not inline-register
 * this elsewhere. First introduced for {@code SubscriberServiceImpl} (Task 3, Epic 05).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
