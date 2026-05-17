package com.botfunnel.funnel;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Placeholder implementation until Epic 06 ships real funnel firing. Both methods complete
 * empty. NOT annotated @Primary — Epic 06 will replace this @Service directly.
 */
@Service
public class NoOpFunnelTriggerService implements FunnelTriggerService {

    @Override
    public Mono<Void> fire(String projectId, Long chatId, String triggerType, String payload) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> cancelActiveFor(String projectId, Long chatId) {
        return Mono.empty();
    }
}
