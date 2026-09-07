package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class EventSenderWithRetry implements EventSender {

    private static final Logger LOGGER = getLogger(EventSenderWithRetry.class);

    private final EventSender delegate;
    private final long[] backoffMs;

    public EventSenderWithRetry(EventSender delegate, long[] backoffMs) {
        this.delegate = delegate;
        this.backoffMs = backoffMs;
    }

    @Override
    public void send(InternalEvent<?> event) {
        for (int attempt = 0; ; attempt++) {
            try {
                this.delegate.send(event);
                return;
            } catch (RuntimeException ex) {
                MonitoringSink.INSTANCE.countRetry();
                long delay = this.backoffMs[Math.min(attempt, this.backoffMs.length - 1)];
                LOGGER.warn("sending event {} failed (attempt {}), retrying in {} ms", event.id(), attempt + 1, delay, ex);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    // shutdown: give up so the receiver loop can exit
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

}
