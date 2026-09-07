package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSender;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSenderWithRetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSenderWithRetryTest {

    private static final InternalEvent<?> EVENT = new InternalEvent("evt-id", InternalEvent.Type.USER, "realm-name", "payload");

    private static class FlakySender implements EventSender {

        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        FlakySender(int failures) {
            this.failures = failures;
        }

        @Override
        public void send(InternalEvent<?> event) {
            if (this.calls.incrementAndGet() <= this.failures) {
                throw new IllegalStateException("boom");
            }
        }
    }

    @Test
    void sendsOnceWhenDelegateSucceeds() {
        FlakySender delegate = new FlakySender(0);

        new EventSenderWithRetry(delegate, new long[]{10}).send(EVENT);

        assertEquals(1, delegate.calls.get());
    }

    @Test
    @Timeout(5)
    void retriesUntilDelegateSucceeds() {
        FlakySender delegate = new FlakySender(6);

        new EventSenderWithRetry(delegate, new long[]{10, 20}).send(EVENT);

        assertEquals(7, delegate.calls.get());
    }

    @Test
    @Timeout(5)
    void stopsRetryingWhenInterrupted() throws InterruptedException {
        FlakySender delegate = new FlakySender(Integer.MAX_VALUE);
        EventSenderWithRetry sender = new EventSenderWithRetry(delegate, new long[]{10_000});

        Thread thread = new Thread(() -> sender.send(EVENT));
        thread.start();
        thread.interrupt();
        thread.join(2_000);

        assertTrue(delegate.calls.get() >= 1);
        assertEquals(Thread.State.TERMINATED, thread.getState());
    }

}
