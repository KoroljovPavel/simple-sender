package com.botfunnel.common.metrics;

import com.botfunnel.JobRunrInMemoryConfig;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

// Boots the full Spring context to verify the explicit @Bean SimpleMeterRegistry
// (Decision 10): without spring-boot-starter-actuator there is no autoconfigured
// MeterRegistry, so AC16 counters would fail to inject. Mongo/Redis beans mocked
// per HealthEndpointTest precedent — no live infrastructure needed for bean-wiring
// assertions.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JobRunrInMemoryConfig.class)
class MeterRegistryConfigTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    MongoClient mongoClient;

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

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
