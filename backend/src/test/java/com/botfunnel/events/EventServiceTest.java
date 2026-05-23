package com.botfunnel.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    EventRepository eventRepository;

    @Test
    void logEvent_persistsEventWithProvidedFields() {
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        EventService service = new EventService(eventRepository);

        Event returned = service.logEvent("user-1", "login_success", "10.0.0.1", "Mozilla/5.0", Map.of("k", "v"));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        Event saved = captor.getValue();
        assertThat(returned).isSameAs(saved);
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getEventType()).isEqualTo("login_success");
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getMetadata()).containsEntry("k", "v");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void logEvent_propagatesRepositoryError() {
        when(eventRepository.save(any(Event.class)))
                .thenThrow(new RuntimeException("mongo down"));
        EventService service = new EventService(eventRepository);

        assertThatThrownBy(() -> service.logEvent("user-1", "login_success", "10.0.0.1", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo down");
    }
}
