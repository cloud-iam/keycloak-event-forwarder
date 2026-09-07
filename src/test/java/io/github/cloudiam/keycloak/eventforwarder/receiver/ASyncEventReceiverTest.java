package io.github.cloudiam.keycloak.eventforwarder.receiver;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSender;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.keycloak.Config.Scope;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ASyncEventReceiverTest {

    @Test
    @Timeout(5)
    void countsDroppedEventsWhenTheQueueIsFullWithDropPolicy() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        double droppedBefore = registry.counter("keycloak_extensions_event_forwarder_dropped").count();

        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventSender stuckSender = event -> {
            sendStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Scope scope = mock(Scope.class);
        when(scope.get("async_receiver_queue_max_size")).thenReturn("2");
        when(scope.get("async_receiver_when_full")).thenReturn("DROP");

        ASyncEventReceiver receiver = new ASyncEventReceiver(stuckSender, EventForwarderConfiguration.createFromScope(scope));
        try {
            receiver.receive(new InternalEvent("e1", InternalEvent.Type.USER, "realm-name", "payload"));
            assertTrue(sendStarted.await(2, TimeUnit.SECONDS)); // e1 taken, sender thread stuck

            receiver.receive(new InternalEvent("e2", InternalEvent.Type.USER, "realm-name", "payload")); // fills slot 1
            receiver.receive(new InternalEvent("e3", InternalEvent.Type.USER, "realm-name", "payload")); // fills slot 2
            receiver.receive(new InternalEvent("e4", InternalEvent.Type.USER, "realm-name", "payload")); // dropped
            receiver.receive(new InternalEvent("e5", InternalEvent.Type.USER, "realm-name", "payload")); // dropped

            assertEquals(2, registry.counter("keycloak_extensions_event_forwarder_dropped").count() - droppedBefore);
        } finally {
            release.countDown();
            receiver.close();
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    @Timeout(5)
    void closeInterruptsTheSenderThreadAndWaitsForItToStop() throws InterruptedException {
        CountDownLatch sendStarted = new CountDownLatch(1);
        EventSender stuckSender = event -> {
            sendStarted.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ASyncEventReceiver receiver = new ASyncEventReceiver(stuckSender, EventForwarderConfiguration.createFromScope(null));
        receiver.receive(new InternalEvent("evt-id", InternalEvent.Type.USER, "realm-name", "payload"));
        assertTrue(sendStarted.await(2_000, java.util.concurrent.TimeUnit.MILLISECONDS));

        // returns only once the sender thread is gone (guarded by @Timeout)
        receiver.close();
    }

}
