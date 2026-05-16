package com.botfunnel.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Boot does not autoconfigure a MeterRegistry without spring-boot-starter-actuator
// (the MetricsAutoConfiguration classes ship in spring-boot-actuator-autoconfigure). The
// 04b webhook counters depend on a MeterRegistry being injectable, so this config registers
// SimpleMeterRegistry explicitly. @ConditionalOnMissingBean keeps this bean out of the way
// if a future Spring Boot upgrade (or the addition of actuator) starts providing its own.
@Configuration
public class MeterRegistryConfig {

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
