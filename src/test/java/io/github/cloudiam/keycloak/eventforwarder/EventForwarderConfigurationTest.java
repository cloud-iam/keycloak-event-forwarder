package io.github.cloudiam.keycloak.eventforwarder;

import org.junit.jupiter.api.Test;
import org.keycloak.Config.Scope;
import org.keycloak.events.EventType;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventForwarderConfigurationTest {

    @Test
    void usesDefaultsWhenNothingIsConfigured() {
        EventForwarderConfiguration cfg = EventForwarderConfiguration.createFromScope(null);

        assertEquals(EventForwarderConfiguration.Transport.AMQP, cfg.getTransport());
        assertEquals(Set.of(EventType.values()), cfg.includedUserEvents());
        assertTrue(cfg.includedAdminEvents());
        assertEquals(65536, cfg.getASyncReceiverQueueMaxSize());
        assertEquals(EventForwarderConfiguration.WhenFull.DROP, cfg.getASyncReceiverWhenFull());
        assertFalse(cfg.getSerializerJsonIsPretty());
        assertEquals(20, cfg.getMaxSendPerSecond());
        assertArrayEquals(new long[]{1000, 3000, 5000, 10000, 30000}, cfg.getRetryBackoffMs());
    }

    @Test
    void parsesRetryBackoffFromScope() {
        Scope scope = mock(Scope.class);
        when(scope.get("retry_backoff_ms")).thenReturn("100, 200 ,300");

        assertArrayEquals(new long[]{100, 200, 300},
                EventForwarderConfiguration.createFromScope(scope).getRetryBackoffMs());
    }

    @Test
    void fallsBackToDefaultBackoffWhenConfiguredEmpty() {
        Scope scope = mock(Scope.class);
        when(scope.get("retry_backoff_ms")).thenReturn("  ");

        assertArrayEquals(new long[]{1000, 3000, 5000, 10000, 30000},
                EventForwarderConfiguration.createFromScope(scope).getRetryBackoffMs());
    }

    @Test
    void readsTransportFromScopeCaseInsensitively() {
        Scope scope = mock(Scope.class);
        when(scope.get("transport")).thenReturn("amqp");

        assertEquals(EventForwarderConfiguration.Transport.AMQP,
                EventForwarderConfiguration.createFromScope(scope).getTransport());
    }

    @Test
    void noneDisablesAllUserEvents() {
        Scope scope = mock(Scope.class);
        when(scope.get("included_user_events")).thenReturn("None");

        assertEquals(Set.of(), EventForwarderConfiguration.createFromScope(scope).includedUserEvents());
    }

    @Test
    void readsExtensionSettingsFromScope() {
        Scope scope = mock(Scope.class);
        when(scope.get("included_user_events")).thenReturn("LOGIN, logout");
        when(scope.get("include_admin_events")).thenReturn("false");
        when(scope.get("async_receiver_queue_max_size")).thenReturn("128");
        when(scope.get("async_receiver_when_full")).thenReturn("block");
        when(scope.get("serializer_json_is_pretty")).thenReturn("true");
        when(scope.get("max_send_per_second")).thenReturn("0");

        EventForwarderConfiguration cfg = EventForwarderConfiguration.createFromScope(scope);

        assertEquals(Set.of(EventType.LOGIN, EventType.LOGOUT), cfg.includedUserEvents());
        assertFalse(cfg.includedAdminEvents());
        assertEquals(128, cfg.getASyncReceiverQueueMaxSize());
        assertEquals(EventForwarderConfiguration.WhenFull.BLOCK, cfg.getASyncReceiverWhenFull());
        assertTrue(cfg.getSerializerJsonIsPretty());
        assertEquals(0, cfg.getMaxSendPerSecond());
    }

}
