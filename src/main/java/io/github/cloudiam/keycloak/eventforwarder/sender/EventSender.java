package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;

public interface EventSender {

    default void onInternalEvent(InternalEvent<?> event) {
        long sentAt = System.currentTimeMillis();
        MonitoringSink.INSTANCE.recordQueuedDuration(sentAt - event.queuedAt());
        try {
            this.send(new InternalEvent<>(event.id(), event.type(), event.realmName(), event.payload(), event.replayed(), event.emittedAt(), event.queuedAt(), sentAt));
        } finally {
            MonitoringSink.INSTANCE.recordTotalSentDuration(System.currentTimeMillis() - sentAt);
        }
    }

    void send(InternalEvent<?> event);

}
