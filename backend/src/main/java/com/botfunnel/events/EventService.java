package com.botfunnel.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    // Fire-and-forget: never throws. A logging failure must not fail the auth flow.
    public void logEvent(String userId, String eventType, String ipAddress, String userAgent,
                         Map<String, Object> metadata) {
        Event event = new Event(userId, eventType, ipAddress, userAgent, metadata, Instant.now());
        eventRepository.save(event)
                .subscribe(null, err -> log.error("Event log failed for {}: {}", eventType, err.getMessage()));
    }
}
