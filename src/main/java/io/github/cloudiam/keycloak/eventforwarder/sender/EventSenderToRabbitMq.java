package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;
import io.github.cloudiam.keycloak.eventforwarder.serializer.EventSerializer;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.LogTrafficListener;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.slf4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.slf4j.LoggerFactory.getLogger;

public class EventSenderToRabbitMq implements EventSender, Closeable {

    private static final Logger LOGGER = getLogger(EventSenderToRabbitMq.class);

    private final BasicProperties USER_PROPS;
    private final BasicProperties ADMIN_PROPS;
    private final BasicProperties USER_REPLAYED_PROPS;
    private final BasicProperties ADMIN_REPLAYED_PROPS;

    private static final Pattern SPECIAL_CHARACTERS = Pattern.compile("[^*#a-zA-Z0-9 _.-]");
    private static final Pattern SPACE = Pattern.compile(" ");
    private static final Pattern DOT = Pattern.compile("\\.");
    private static final Pattern MUSTACHE_VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    private final EventForwarderConfiguration config;
    private final ConnectionFactory connectionFactory;
    private final EventSerializer<String> serializer;
    private Connection connection;
    private Channel channel;

    public EventSenderToRabbitMq(EventForwarderConfiguration config,
                                 ConnectionFactory connectionFactory,
                                 EventSerializer<String> serializer) {
        this.config = config;
        this.connectionFactory = connectionFactory;
        this.serializer = serializer;

        USER_PROPS = messageProps(config.getAMQPUserTypeId(), false);
        ADMIN_PROPS = messageProps(config.getAMQPAdminTypeId(), false);
        USER_REPLAYED_PROPS = messageProps(config.getAMQPUserTypeId(), true);
        ADMIN_REPLAYED_PROPS = messageProps(config.getAMQPAdminTypeId(), true);
    }

    @Override
    public void send(InternalEvent<?> event) {
        String routingKey = routingKey(event);

        byte[] payload = this.serializer.serialize(event).getBytes(StandardCharsets.UTF_8);

        MonitoringSink.INSTANCE.recordPayloadSize(payload.length);
        final long startedAt = System.currentTimeMillis();
        try {
            LOGGER.trace("sending over rabbitmq {} to {}", event.id(), routingKey);
            this.checkConnectionAndChannel();
            LOGGER.trace("connexion acquired to send {}", event.id());
            this.channel.basicPublish(this.config.getExchange(), routingKey, messageProps(event), payload);
            LOGGER.trace("message {} sent", event.id());
        } catch (IOException | TimeoutException ex) {
            LOGGER.error("error sending message: {} {}", event.id(), routingKey, ex);
            // unchecked so decorators like EventSenderWithRetry can react
            throw new IllegalStateException("could not send event to rabbitmq", ex);
        } finally {
            MonitoringSink.INSTANCE.recordRabbitMQSentDuration(System.currentTimeMillis() - startedAt);
        }
    }

    @Override
    public void close() {
        try {
            if (this.channel != null && this.channel.isOpen()) {
                this.channel.close();
            }
            if (this.connection != null && this.connection.isOpen()) {
                this.connection.close();
            }
        } catch (IOException | TimeoutException ex) {
            LOGGER.error("event-forwarder ERROR on close", ex);
        }
    }

    private void checkConnectionAndChannel() throws IOException, TimeoutException {
        if (this.connection == null || !this.connection.isOpen()) {
            this.connection = this.connectionFactory.newConnection();
        }
        if (this.channel == null || !this.channel.isOpen()) {
            this.channel = this.connection.createChannel();
        }
    }

    private String routingKey(InternalEvent<?> event) {
        return switch (event.type()) {
            case USER -> calculateUserEventRoutingKey((InternalEvent<Event>) event);
            case ADMIN -> calculateAdminEventRoutingKey((InternalEvent<AdminEvent>) event);
        };
    }


    // default pattern: KK.EVENT.ADMIN.{{realm}}.{{result}}.{{resource_type}}.{{operation}}
    public String calculateAdminEventRoutingKey(InternalEvent<AdminEvent> event) {
        AdminEvent payload = event.payload();
        Map<String, String> variables = new HashMap<>();
        variables.put("realm", String.valueOf(removeDots(event.realmName())));
        variables.put("result", payload.getError() != null ? "ERROR" : "SUCCESS");
        variables.put("resource_type", String.valueOf(payload.getResourceTypeAsString()));
        variables.put("operation", String.valueOf(payload.getOperationType()));
        return normalizeKey(interpolate(this.config.getAmqpAdminRoutingKeyPattern(), variables));
    }

    // default pattern: KK.EVENT.CLIENT.{{realm}}.{{result}}.{{client}}.{{type}}
    public String calculateUserEventRoutingKey(InternalEvent<Event> event) {
        Event payload = event.payload();
        Map<String, String> variables = new HashMap<>();
        variables.put("realm", String.valueOf(removeDots(event.realmName())));
        variables.put("result", payload.getError() != null ? "ERROR" : "SUCCESS");
        variables.put("client", String.valueOf(removeDots(payload.getClientId())));
        variables.put("type", String.valueOf(payload.getType()));
        return normalizeKey(interpolate(this.config.getAmqpUserRoutingKeyPattern(), variables));
    }

    /**
     * Replaces every mustache style variable of the pattern, e.g. "kk.{{varname}}.{{another}}.ok",
     * by its value from the map. Unknown variables are kept as-is so a typo stays visible.
     */
    public static String interpolate(String pattern, Map<String, String> variables) {
        return MUSTACHE_VARIABLE.matcher(pattern).replaceAll(match -> {
            String value = variables.get(match.group(1));
            return Matcher.quoteReplacement(value != null ? value : match.group());
        });
    }

    //Remove all characters apart a-z, A-Z, 0-9, space, underscore, replace all spaces and hyphens with underscore
    public static String normalizeKey(CharSequence stringToNormalize) {
        return SPACE.matcher(SPECIAL_CHARACTERS.matcher(stringToNormalize).replaceAll(""))
                .replaceAll("_");
    }

    public static String removeDots(String stringToNormalize) {
        if (stringToNormalize != null) {
            return DOT.matcher(stringToNormalize).replaceAll("");
        }
        return stringToNormalize;
    }


    private BasicProperties messageProps(InternalEvent<?> event) {
        if (event.type() == InternalEvent.Type.ADMIN) {
            return event.replayed() ? ADMIN_REPLAYED_PROPS : ADMIN_PROPS;
        }
        return event.replayed() ? USER_REPLAYED_PROPS : USER_PROPS;
    }

    private static BasicProperties messageProps(String className, boolean replayed) {
        Map<String, Object> headers = replayed
                ? Map.of("__TypeId__", className, "x-replayed", "true")
                : Map.of("__TypeId__", className);
        return new AMQP.BasicProperties.Builder()
                .appId("Keycloak")
                .headers(headers)
                .contentType("application/json")
                .contentEncoding("UTF-8")
                .build();
    }

}
