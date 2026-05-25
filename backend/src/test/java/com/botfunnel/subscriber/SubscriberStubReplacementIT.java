package com.botfunnel.subscriber;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// AC24 / Decision 1: after NoOpSubscriberService deletion there is exactly ONE SubscriberService bean
// and it is the real SubscriberServiceImpl — proving the stub was replaced, not shadowed via @Primary.
class SubscriberStubReplacementIT extends AbstractIntegrationTest {

    @Autowired ApplicationContext applicationContext;

    @Test
    void onlyOneSubscriberServiceBean_andItIsSubscriberServiceImpl() {
        Map<String, SubscriberService> beans = applicationContext.getBeansOfType(SubscriberService.class);

        assertThat(beans).hasSize(1);
        assertThat(beans.values().iterator().next().getClass().getSimpleName())
                .isEqualTo("SubscriberServiceImpl");
    }
}
