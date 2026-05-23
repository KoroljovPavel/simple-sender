package com.botfunnel.funnel;

import org.springframework.stereotype.Service;

/**
 * Placeholder implementation until Epic 06 ships real funnel firing. Both methods return
 * without side effects. NOT annotated @Primary — Epic 06 will replace this @Service directly.
 */
@Service
public class NoOpFunnelTriggerService implements FunnelTriggerService {

    @Override
    public void fire(String projectId, Long chatId, String triggerType, String payload) {
        // no-op
    }

    @Override
    public void cancelActiveFor(String projectId, Long chatId) {
        // no-op
    }
}
