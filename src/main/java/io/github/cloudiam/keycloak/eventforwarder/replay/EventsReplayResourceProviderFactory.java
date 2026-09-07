package io.github.cloudiam.keycloak.eventforwarder.replay;

import org.keycloak.Config.Scope;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public class EventsReplayResourceProviderFactory implements RealmResourceProviderFactory {

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new EventsReplayResourceProvider(session);
    }

    @Override
    public void init(Scope config) {

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return "event-forwarder";
    }

}
