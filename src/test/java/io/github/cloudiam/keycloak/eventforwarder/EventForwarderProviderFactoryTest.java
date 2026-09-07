package io.github.cloudiam.keycloak.eventforwarder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.keycloak.Config.Scope;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventForwarderProviderFactoryTest {

    @Test
    @Timeout(5)
    void buildsThePipelineAndCreatesACaptorEnlistedInTheTransaction() {
        EventForwarderProviderFactory factory = new EventForwarderProviderFactory();
        factory.init(null);
        try {
            KeycloakSession session = mock(KeycloakSession.class);
            KeycloakTransactionManager transactionManager = mock(KeycloakTransactionManager.class);
            when(session.getTransactionManager()).thenReturn(transactionManager);

            EventListenerProvider provider = factory.create(session);

            assertInstanceOf(EventCaptor.class, provider);
            verify(transactionManager).enlistAfterCompletion(any());
        } finally {
            factory.close();
        }
    }

    @Test
    @Timeout(5)
    void usesTheSynchronousReceiverWhenQueueSizeIsZero() {
        Scope scope = mock(Scope.class);
        when(scope.get("async_receiver_queue_max_size")).thenReturn("0");

        EventForwarderProviderFactory factory = new EventForwarderProviderFactory();
        // the async receiver would reject a queue capacity of 0 (LinkedBlockingQueue)
        factory.init(scope);
        factory.close();
    }

    @Test
    void keepsTheHistoricalProviderId() {
        assertEquals("event-forwarder", new EventForwarderProviderFactory().getId());
    }

}
