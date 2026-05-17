package com.botfunnel.funnel;

import reactor.core.publisher.Mono;

/**
 * Contract consumed by the webhook worker (ProcessTelegramUpdateJob) for funnel side effects.
 * The real implementation is deferred to Epic 06; Epic 04b ships a no-op @Service so the
 * worker call sites compile and integration tests run without firing real funnels.
 */
public interface FunnelTriggerService {

    Mono<Void> fire(String projectId, Long chatId, String triggerType, String payload);

    Mono<Void> cancelActiveFor(String projectId, Long chatId);
}
