package com.botfunnel.events;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Persists audit events synchronously. Under virtual threads the embedded {@code save(...)} is
 * cheap, so the prior split between a fire-and-forget and a blocking variant collapses to a
 * single throwing method.
 *
 * <p>Contract: a persistence failure propagates to the caller (unchecked exception from the
 * underlying driver). Callers that need silent-failure semantics must wrap the call themselves;
 * none currently do.
 */
@Service
public class EventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public Event logEvent(String userId, String eventType, String ipAddress, String userAgent,
                          Map<String, Object> metadata) {
        Event event = new Event(userId, eventType, ipAddress, userAgent, metadata, Instant.now());
        return eventRepository.save(event);
    }
}
