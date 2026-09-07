package io.github.cloudiam.keycloak.eventforwarder.replay;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public class EventsReplayResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public EventsReplayResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return new EventsReplayResource(this.session);
    }

    @Override
    public void close() {

    }

}
