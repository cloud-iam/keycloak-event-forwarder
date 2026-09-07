package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class EventSenderToLogs implements EventSender {

    private static final Logger LOGGER = getLogger(EventSenderToLogs.class);

    @Override
    public void send(InternalEvent<?> event) {
        LOGGER.info("{}", event.payload());
    }

}
