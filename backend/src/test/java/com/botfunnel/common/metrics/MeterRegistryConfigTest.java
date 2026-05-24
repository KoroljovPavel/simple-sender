package com.botfunnel.common.metrics;

import com.botfunnel.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// Boots the full Spring context to verify the explicit @Bean SimpleMeterRegistry
// (Decision 10): without spring-boot-starter-actuator there is no autoconfigured
// MeterRegistry, so AC16 counters would fail to inject. After the servlet flip the
// prior slice-style MockitoBean MongoClient/RedisConnectionFactory pattern stopped
// working (MongoTemplate constructs eagerly); inherits AbstractIntegrationTest's
// Testcontainers Mongo/Redis instead.
class MeterRegistryConfigTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void contextLoads_meterRegistryBeanRegistered() {
        MeterRegistry fromContext = applicationContext.getBean(MeterRegistry.class);
        assertThat(fromContext).isNotNull();
        assertThat(fromContext).isInstanceOf(SimpleMeterRegistry.class);
    }

    @Test
    void findUnknownMetric_returnsNullNotNpe() {
        Counter counter = meterRegistry.find("nonexistent.metric").counter();
        assertThat(counter).isNull();
    }
}
