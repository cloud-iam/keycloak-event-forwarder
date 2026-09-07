package io.github.cloudiam.keycloak.eventforwarder;

import io.github.cloudiam.keycloak.eventforwarder.receiver.EventReceiver;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Filtering and publication rules shared by the live capture ({@link EventCaptor})
 * and the replay of previously saved events.
 */
public class EventPublisher {

    private static final Logger LOGGER = getLogger(EventPublisher.class);

    private final EventForwarderConfiguration configuration;
    private final EventReceiver receiver;

    public EventPublisher(EventForwarderConfiguration configuration,
                          EventReceiver receiver) {
        this.configuration = configuration;
        this.receiver = receiver;
    }

    public boolean accepts(Event event) {
        return this.configuration.includedUserEvents().contains(event.getType());
    }

    public boolean acceptsAdminEvents() {
        return this.configuration.includedAdminEvents();
    }

    public void publish(Event event, final String realmName, boolean replayed) {
        this.publish(new InternalEvent<>(event.getId(), InternalEvent.Type.USER, realmName, event, replayed));
    }

    public void publish(AdminEvent event, final String realmName, boolean replayed) {
        this.publish(new InternalEvent<>(event.getId(), InternalEvent.Type.ADMIN, realmName, event, replayed));
    }

    private void publish(InternalEvent<?> event) {
        LOGGER.trace("publishing event: {}", event.id());
        this.receiver.receive(event);
    }

}
