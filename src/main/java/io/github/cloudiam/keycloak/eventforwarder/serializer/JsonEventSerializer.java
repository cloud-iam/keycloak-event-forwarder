package io.github.cloudiam.keycloak.eventforwarder.serializer;

import org.keycloak.util.JsonSerialization;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class JsonEventSerializer implements EventSerializer<String> {

    private static final Logger LOGGER = getLogger(JsonEventSerializer.class);

    private final boolean pretty;

    public JsonEventSerializer() {
        this(false);
    }

    public JsonEventSerializer(final boolean pretty) {
        this.pretty = pretty;
    }

    public String doSerialize(Object event) {
        try {
            if (this.pretty) {
                return JsonSerialization.writeValueAsPrettyString(event);
            }
            return JsonSerialization.writeValueAsString(event);

        } catch (Exception e) {
            LOGGER.error("Could not serialize to JSON", e);
        }
        return "unparseable";
    }

}
