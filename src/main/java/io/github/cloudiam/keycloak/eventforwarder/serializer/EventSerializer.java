package io.github.cloudiam.keycloak.eventforwarder.serializer;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public interface EventSerializer<O> {

    Logger LOGGER = getLogger(EventSerializer.class);

    default O serialize(InternalEvent<?> event) {
        return switch (event.type()) {
            case USER -> serializeUserEvent((Event) event.payload());
            case ADMIN -> serializeAdminEvent((AdminEvent) event.payload());
        };
    }

    default O serializeUserEvent(Event event) {
        return serializeSafely(event);
    }
    default O serializeAdminEvent(AdminEvent event) {
        return serializeSafely(event);
    }

    default O serializeSafely(Object object) {
        long startedAt = System.currentTimeMillis();
        try {
            return doSerialize(object);
        } catch (Exception any) {
            LOGGER.error("Error serializing event", any);
            return null;
        } finally {
            MonitoringSink.INSTANCE.recordSerializationDuration(System.currentTimeMillis() - startedAt);
        }
    }


    O doSerialize(Object any);

}
