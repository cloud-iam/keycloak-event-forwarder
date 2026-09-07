package io.github.cloudiam.keycloak.eventforwarder.receiver;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;

public interface EventReceiver {

    default void receive(InternalEvent<?> event) {
        this.received(new InternalEvent<>(event.id(), event.type(), event.realmName(), event.payload(), event.replayed(), event.emittedAt(), System.currentTimeMillis(), 0L));
    }

    void received(InternalEvent<?> event);

}
