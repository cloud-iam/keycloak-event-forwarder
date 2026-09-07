package io.github.cloudiam.keycloak.eventforwarder.replay;

import io.github.cloudiam.keycloak.eventforwarder.EventPublisher;
import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import io.github.cloudiam.keycloak.eventforwarder.replay.EventsReplayService.ReplayResult;
import io.github.cloudiam.keycloak.eventforwarder.replay.EventsReplayService.ReplayUnavailableException;
import org.junit.jupiter.api.Test;
import org.keycloak.Config.Scope;
import org.keycloak.events.Event;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventsReplayServiceTest {

    private final List<InternalEvent> published = new ArrayList<>();
    private final KeycloakSession session = mock(KeycloakSession.class);
    private final RealmModel realm = mock(RealmModel.class);
    private final EventStoreProvider store = mock(EventStoreProvider.class);

    private EventsReplayService service(String includedUserEvents, String includeAdminEvents) {
        Scope scope = mock(Scope.class);
        when(scope.get("included_user_events")).thenReturn(includedUserEvents);
        when(scope.get("include_admin_events")).thenReturn(includeAdminEvents);
        EventForwarderConfiguration configuration = EventForwarderConfiguration.createFromScope(scope);
        return new EventsReplayService(new EventPublisher(configuration, this.published::add));
    }

    private void storeReturns(List<Event> userEvents, List<AdminEvent> adminEvents) {
        when(this.session.getProvider(EventStoreProvider.class)).thenReturn(this.store);
        var query = mock(org.keycloak.events.EventQuery.class, RETURNS_SELF);
        when(query.getResultStream()).thenAnswer(i -> userEvents.stream());
        when(this.store.createQuery()).thenReturn(query);
        var adminQuery = mock(org.keycloak.events.admin.AdminEventQuery.class, RETURNS_SELF);
        when(adminQuery.getResultStream()).thenAnswer(i -> adminEvents.stream());
        when(this.store.createAdminQuery()).thenReturn(adminQuery);
    }

    private static Event userEvent(String id, EventType type) {
        Event event = new Event();
        event.setId(id);
        event.setType(type);
        return event;
    }

    private static AdminEvent adminEvent(String id) {
        AdminEvent event = new AdminEvent();
        event.setId(id);
        return event;
    }

    @Test
    void replaysFilteredUserAndAdminEventsAsReplayed() {
        storeReturns(
                List.of(userEvent("u1", EventType.LOGIN), userEvent("u2", EventType.LOGOUT), userEvent("u3", EventType.LOGIN)),
                List.of(adminEvent("a1")));

        ReplayResult result = service("LOGIN", "true").replay(this.session, this.realm, new Date(0), new Date(), 100);

        assertEquals(2, result.userEvents()); // LOGOUT filtered out
        assertEquals(1, result.adminEvents());
        assertEquals(3, this.published.size());
        assertTrue(this.published.stream().allMatch(InternalEvent::replayed));
        assertEquals(List.of("u1", "u3", "a1"), this.published.stream().map(InternalEvent::id).toList());
    }

    @Test
    void skipsAdminEventsWhenExcludedByConfiguration() {
        storeReturns(List.of(), List.of(adminEvent("a1")));

        ReplayResult result = service("*", "false").replay(this.session, this.realm, new Date(0), new Date(), 100);

        assertEquals(0, result.adminEvents());
        assertTrue(this.published.isEmpty());
    }

    @Test
    void returnsZeroCountsWhenNothingIsStored() {
        storeReturns(List.of(), List.of());

        ReplayResult result = service("*", "true").replay(this.session, this.realm, new Date(0), new Date(), 100);

        assertEquals(new ReplayResult(0, 0), result);
    }

    @Test
    void failsClearlyWithoutAnEventStore() {
        when(this.session.getProvider(EventStoreProvider.class)).thenReturn(null);

        assertThrows(ReplayUnavailableException.class,
                () -> service("*", "true").replay(this.session, this.realm, new Date(0), new Date(), 100));
    }

}
