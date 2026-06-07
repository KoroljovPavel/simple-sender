package com.botfunnel.subscriber;

import com.botfunnel.events.EventService;
import com.botfunnel.tag.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit test for the project-scoped {@code findById} boundary method that Task 4 owns
 * (Task 7 reuses it). The anti-IDOR contract — empty when the subscriberId belongs to a different
 * project — is the load-bearing assertion.
 */
@ExtendWith(MockitoExtension.class)
class SubscriberServiceImplTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String OTHER_PROJECT_ID = "proj-2";
    private static final String SUBSCRIBER_ID = "sub-1";

    @Mock SubscriberRepository subscriberRepository;
    @Mock SubscriberEventRepository subscriberEventRepository;
    @Mock TagService tagService;
    @Mock MongoTemplate mongoTemplate;
    @Mock StringRedisTemplate redisTemplate;
    @Mock EventService eventService;

    private SubscriberServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC);
        service = new SubscriberServiceImpl(subscriberRepository, subscriberEventRepository, tagService,
                mongoTemplate, redisTemplate, eventService, clock, 100);
    }

    @Test
    void findById_projectScoped_returnsOnlyOwnProjectSubscriber() {
        Subscriber own = subscriber(PROJECT_ID);
        when(subscriberRepository.findById(SUBSCRIBER_ID)).thenReturn(Optional.of(own));

        assertThat(service.findById(PROJECT_ID, SUBSCRIBER_ID)).containsSame(own);
    }

    @Test
    void findById_otherProjectSubscriber_returnsEmpty() {
        Subscriber foreign = subscriber(OTHER_PROJECT_ID);
        when(subscriberRepository.findById(SUBSCRIBER_ID)).thenReturn(Optional.of(foreign));

        // Anti-IDOR: id belongs to a different project → empty, not the foreign subscriber.
        assertThat(service.findById(PROJECT_ID, SUBSCRIBER_ID)).isEmpty();
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        when(subscriberRepository.findById(SUBSCRIBER_ID)).thenReturn(Optional.empty());

        assertThat(service.findById(PROJECT_ID, SUBSCRIBER_ID)).isEmpty();
    }

    @Test
    void findById_nullId_returnsEmpty() {
        assertThat(service.findById(PROJECT_ID, null)).isEmpty();
    }

    private Subscriber subscriber(String projectId) {
        Subscriber s = new Subscriber();
        s.setId(SUBSCRIBER_ID);
        s.setProjectId(projectId);
        s.setStatus(SubscriberStatus.ACTIVE);
        return s;
    }
}
