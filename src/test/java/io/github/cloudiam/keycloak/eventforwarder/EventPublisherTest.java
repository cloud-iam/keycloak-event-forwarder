package io.github.cloudiam.keycloak.eventforwarder;

import io.github.cloudiam.keycloak.eventforwarder.receiver.EventReceiver;
import org.junit.jupiter.api.Test;
import org.keycloak.Config.Scope;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventPublisherTest {

    private final List<InternalEvent<?>> received = new ArrayList<>();
    private final EventReceiver receiver = this.received::add;

    private EventPublisher publisher(String includedUserEvents, String includeAdminEvents) {
        Scope scope = mock(Scope.class);
        when(scope.get("included_user_events")).thenReturn(includedUserEvents);
        when(scope.get("include_admin_events")).thenReturn(includeAdminEvents);
        EventForwarderConfiguration configuration = EventForwarderConfiguration.createFromScope(scope);
        return new EventPublisher(configuration, this.receiver);
    }

    private static Event userEvent(String id, EventType type) {
        Event event = new Event();
        event.setId(id);
        event.setType(type);
        return event;
    }

    @Test
    void acceptsHonorsTheConfiguredUserEventTypes() {
        EventPublisher publisher = publisher("LOGIN", "true");

        assertTrue(publisher.accepts(userEvent("evt-1", EventType.LOGIN)));
        assertFalse(publisher.accepts(userEvent("evt-2", EventType.LOGOUT)));
    }

    @Test
    void acceptsAdminEventsHonorsTheConfiguration() {
        assertTrue(publisher("*", "true").acceptsAdminEvents());
        assertFalse(publisher("*", "false").acceptsAdminEvents());
    }

    @Test
    void publishesUserEventWithOriginalIdAndReplayedFlag() {
        publisher("*", "true").publish(userEvent("evt-42", EventType.LOGIN), "realm-name", true);

        assertEquals(1, this.received.size());
        InternalEvent<?> event = this.received.getFirst();
        assertEquals("evt-42", event.id());
        assertEquals(InternalEvent.Type.USER, event.type());
        assertTrue(event.replayed());
        assertEquals("evt-42", ((Event) event.payload()).getId());
    }

    @Test
    void publishesAdminEventAsAdminType() {
        AdminEvent adminEvent = new AdminEvent();
        adminEvent.setId("adm-1");
        adminEvent.setOperationType(OperationType.CREATE);

        publisher("*", "true").publish(adminEvent, "realm-name", false);

        assertEquals(1, this.received.size());
        InternalEvent<?> event = this.received.getFirst();
        assertEquals("adm-1", event.id());
        assertEquals(InternalEvent.Type.ADMIN, event.type());
        assertFalse(event.replayed());
    }

}
