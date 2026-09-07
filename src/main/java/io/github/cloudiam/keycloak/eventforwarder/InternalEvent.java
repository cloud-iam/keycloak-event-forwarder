package io.github.cloudiam.keycloak.eventforwarder;

public record InternalEvent<E>(
        String id,
        Type type,
        String realmName,
        E payload,
        boolean replayed,
        long emittedAt,
        long queuedAt,
        long sentAt
) {
    public enum Type {
        USER,
        ADMIN
    }

    public InternalEvent(String id, Type type, String realmName, E payload) {
        this(id, type, realmName, payload, false);
    }

    public InternalEvent(String id, Type type, String realmName, E payload, boolean replayed) {
        this(id, type, realmName, payload, replayed, System.currentTimeMillis(), System.currentTimeMillis(), 0L);
    }
}
