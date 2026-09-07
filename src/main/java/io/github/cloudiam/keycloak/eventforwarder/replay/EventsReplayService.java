package io.github.cloudiam.keycloak.eventforwarder.replay;

import io.github.cloudiam.keycloak.eventforwarder.EventPublisher;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.slf4j.Logger;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Replays events previously saved in the Keycloak event store through the
 * regular publication pipeline (filtering, buffering, rate limiting, retry).
 */
public class EventsReplayService {

    private static final Logger LOGGER = getLogger(EventsReplayService.class);

    private final EventPublisher publisher;

    public EventsReplayService(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public record ReplayResult(int userEvents, int adminEvents) {
    }

    /**
     * Thrown when the realm has no usable event store ("Save events" disabled).
     */
    public static class ReplayUnavailableException extends RuntimeException {
        public ReplayUnavailableException(String message) {
            super(message);
        }
    }

    public ReplayResult replay(KeycloakSession session, RealmModel realm, Date from, Date to, int maxEvents) {
        EventStoreProvider store = session.getProvider(EventStoreProvider.class);
        if (store == null) {
            throw new ReplayUnavailableException("no event store available, enable 'Save events' for the realm");
        }

        AtomicInteger userEvents = new AtomicInteger();
        store.createQuery()
                .realm(realm.getId())
                .fromDate(from)
                .toDate(to)
                .orderByAscTime()
                .maxResults(maxEvents)
                .getResultStream()
                .filter(this.publisher::accepts)
                .forEach(event -> {
                    this.publisher.publish(event,realm.getName(), true);
                    userEvents.incrementAndGet();
                });

        AtomicInteger adminEvents = new AtomicInteger();
        if (this.publisher.acceptsAdminEvents()) {
            store.createAdminQuery()
                    .realm(realm.getId())
                    .fromTime(from)
                    .toTime(to)
                    .orderByAscTime()
                    .maxResults(maxEvents)
                    .getResultStream()
                    .forEach(event -> {
                        this.publisher.publish(event,realm.getName(), true);
                        adminEvents.incrementAndGet();
                    });
        }

        LOGGER.info("replayed {} user events and {} admin events of realm {} between {} and {}",
                userEvents.get(), adminEvents.get(), realm.getName(), from, to);
        return new ReplayResult(userEvents.get(), adminEvents.get());
    }

}
