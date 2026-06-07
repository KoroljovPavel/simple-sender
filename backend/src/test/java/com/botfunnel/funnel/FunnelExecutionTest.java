package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision 6: {@code enrollDepth} is the Redis-independent enroll-chain depth backstop carried on each
 * execution. A fresh execution (a human/external/on_start root) must default to 0 — the primitive
 * {@code int} default — so an un-set root is correctly treated as depth 0, never as a started chain.
 */
class FunnelExecutionTest {

    @Test
    void enrollDepth_defaultsToZero() {
        assertThat(new FunnelExecution().getEnrollDepth()).isZero();
    }
}
