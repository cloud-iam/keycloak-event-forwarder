package io.github.cloudiam.keycloak.eventforwarder.receiver;

import io.github.cloudiam.keycloak.eventforwarder.sender.EventSender;
import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class SyncEventReceiver implements EventReceiver {
    private static final Logger LOGGER = getLogger(SyncEventReceiver.class);

    private final EventSender sender;

    public SyncEventReceiver(EventSender sender) {
        this.sender = sender;
    }

    @Override
    public void received(InternalEvent<?> event) {
        LOGGER.trace("direct publishing: {}", event.id());
        this.sender.onInternalEvent(event);
    }
}
