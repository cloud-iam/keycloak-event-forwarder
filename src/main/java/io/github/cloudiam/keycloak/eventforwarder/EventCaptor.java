package io.github.cloudiam.keycloak.eventforwarder;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class EventCaptor implements EventListenerProvider {

    private static final Logger LOGGER = getLogger(EventCaptor.class);

    private final EventListenerTransaction tx = new EventListenerTransaction(this::publishAdminEvent, this::publishUserEvent);

    private final KeycloakSession session;
    private final EventPublisher publisher;

    public EventCaptor(KeycloakSession session, EventPublisher publisher) {
        this.session = session;
        this.publisher = publisher;
        session.getTransactionManager().enlistAfterCompletion(this.tx);
    }

    @Override
    public void close() {

    }

    @Override
    public void onEvent(Event event) {
        if (this.publisher.accepts(event)) {
            LOGGER.trace("captured user event: {} {}", event.getId(), event.getType());
            tx.addEvent(event.clone());
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (this.publisher.acceptsAdminEvents()) {
            LOGGER.trace("captured admin event: {} {} {}", event.getId(), event.getOperationType(), event.getResourceTypeAsString());
            tx.addAdminEvent(new AdminEvent(event), includeRepresentation);
        }
    }

    private void publishUserEvent(Event original) {
        this.publisher.publish(original, session.realms().getRealm(original.getRealmId()).getName(), false);
    }

    private void publishAdminEvent(AdminEvent original, boolean includeRepresentation) {
        this.publisher.publish(original, session.getContext().getRealm().getName(), false);
    }

}
