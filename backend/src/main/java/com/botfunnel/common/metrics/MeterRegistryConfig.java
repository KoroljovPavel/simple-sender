package com.botfunnel.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit {@link MeterRegistry} bean.
 *
 * Spring Boot does not autoconfigure a MeterRegistry without
 * {@code spring-boot-starter-actuator} (the {@code MetricsAutoConfiguration} classes ship
 * in {@code spring-boot-actuator-autoconfigure}). The 04b webhook counters depend on a
 * MeterRegistry being injectable, so this config registers {@code SimpleMeterRegistry}
 * explicitly.
 *
 * {@code @ConditionalOnMissingBean} keeps this bean out of the way if a future Spring Boot
 * upgrade (or the addition of actuator) starts providing its own.
 */
@Configuration
public class MeterRegistryConfig {

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
