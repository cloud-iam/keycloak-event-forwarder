package io.github.cloudiam.keycloak.eventforwarder;


import com.rabbitmq.client.ConnectionFactory;
import org.keycloak.Config.Scope;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;


public class EventForwarderConfiguration {

    private static final Logger LOGGER = getLogger(EventForwarderConfiguration.class);

    // delays (ms) between send retries; the last value repeats for every further attempt
    private static final String DEFAULT_RETRY_BACKOFF_MS = "1000,3000,5000,10000,30000";

    // target transport the events are forwarded to; only AMQP is implemented today
    private Transport transport;

    private String hostUrl;
    private Integer port;
    private String username;
    private String password;
    private String vhost;
    private Boolean useTls;
    private Integer connectionTimeout;
    private Integer handshakeTimeout;

    // SSL context settings
    private String trustStore;
    private String trustStorePass;
    private String keyStore;
    private String keyStorePass;
    //


    private String exchange;

    private Set<EventType> includedUserEvents;
    private Boolean includeAdminEvents;
    private Integer aSyncReceiverQueueMaxSize;
    private WhenFull aSyncReceiverWhenFull;
    private Boolean serializerJsonIsPretty;
    private Integer maxSendPerSecond;
    private long[] retryBackoffMs;
    private String amqpUserTypeId;
    private String amqpUserRoutingKeyPattern;
    private String amqpAdminTypeId;
    private String amqpAdminRoutingKeyPattern;

    public Transport getTransport() {
        return this.transport;
    }

    public String getAMQPUserTypeId() {
        return this.amqpUserTypeId;
    }

    public String getAMQPAdminTypeId() {
        return this.amqpAdminTypeId;
    }

    public String getAmqpUserRoutingKeyPattern() {
        return amqpUserRoutingKeyPattern;
    }

    public String getAmqpAdminRoutingKeyPattern() {
        return amqpAdminRoutingKeyPattern;
    }

    public enum WhenFull {
        BLOCK,
        DROP,
    }

    // forwarding targets; extend this enum (and wire a sender) when adding a new transport
    public enum Transport {
        AMQP,
    }

    public static EventForwarderConfiguration createFromScope(Scope config) {
        EventForwarderConfiguration cfg = new EventForwarderConfiguration();

        cfg.transport = Transport.valueOf(resolveConfigVar(config, "transport", Transport.AMQP.name()).toUpperCase(Locale.ENGLISH));

        cfg.hostUrl = resolveConfigVar(config, "amqp_url", "localhost");
        cfg.port = Integer.valueOf(resolveConfigVar(config, "amqp_port", "5672"));
        cfg.username = resolveConfigVar(config, "amqp_username", "admin");
        cfg.password = resolveConfigVar(config, "amqp_password", "admin");
        cfg.vhost = resolveConfigVar(config, "amqp_vhost", "");
        cfg.useTls = Boolean.valueOf(resolveConfigVar(config, "amqp_use_tls", "false"));
        cfg.connectionTimeout = Integer.valueOf(resolveConfigVar(config, "amqp_connection_timeout", String.valueOf(ConnectionFactory.DEFAULT_CONNECTION_TIMEOUT)));
        cfg.handshakeTimeout = Integer.valueOf(resolveConfigVar(config, "amqp_handshake_timeout", String.valueOf(ConnectionFactory.DEFAULT_HANDSHAKE_TIMEOUT)));

        // SSL context settings
        cfg.trustStore = resolveConfigVar(config, "amqp_trust_store", "");
        cfg.trustStorePass = resolveConfigVar(config, "amqp_trust_store_pass", "");
        cfg.keyStore = resolveConfigVar(config, "amqp_key_store", "");
        cfg.keyStorePass = resolveConfigVar(config, "amqp_key_store_pass", "");
        //

        cfg.exchange = resolveConfigVar(config, "amqp_exchange", "amq.topic");

        cfg.includedUserEvents = parseUserEvents(resolveConfigVar(config, "included_user_events", "*"));
        cfg.includeAdminEvents = Boolean.valueOf(resolveConfigVar(config, "include_admin_events", "true"));
        cfg.aSyncReceiverQueueMaxSize = Integer.valueOf(resolveConfigVar(config, "async_receiver_queue_max_size", "65536"));
        cfg.aSyncReceiverWhenFull = WhenFull.valueOf(resolveConfigVar(config, "async_receiver_when_full", WhenFull.DROP.name()).toUpperCase(Locale.ENGLISH));
        cfg.serializerJsonIsPretty = Boolean.valueOf(resolveConfigVar(config, "serializer_json_is_pretty", "false"));
        cfg.maxSendPerSecond = Integer.valueOf(resolveConfigVar(config, "max_send_per_second", "20"));
        cfg.retryBackoffMs = parseBackoff(resolveConfigVar(config, "retry_backoff_ms", DEFAULT_RETRY_BACKOFF_MS));

        cfg.amqpUserTypeId = resolveConfigVar(config, "amqp_user_type_id", Event.class.getCanonicalName());
        cfg.amqpAdminTypeId = resolveConfigVar(config, "amqp_admin_type_id", AdminEvent.class.getCanonicalName());

        cfg.amqpUserRoutingKeyPattern = resolveConfigVar(config, "amqp_user_routing_key_pattern", "KK.EVENT.CLIENT.{{realm}}.{{result}}.{{client}}.{{type}}");
        cfg.amqpAdminRoutingKeyPattern = resolveConfigVar(config, "amqp_admin_routing_key_pattern", "KK.EVENT.ADMIN.{{realm}}.{{result}}.{{resource_type}}.{{operation}}");

        return cfg;

    }

    // comma separated list of delays in ms, e.g. "1000,3000,5000"; falls back to the default when empty
    private static long[] parseBackoff(String configured) {
        long[] backoff = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .mapToLong(Long::parseLong)
                .toArray();
        if (backoff.length == 0) {
            LOGGER.warn("retry_backoff_ms resolved to an empty list, falling back to defaults");
            return Arrays.stream(DEFAULT_RETRY_BACKOFF_MS.split(",")).mapToLong(Long::parseLong).toArray();
        }
        return backoff;
    }

    // comma separated list of EventType names, "*" for all of them, "none" for none of them
    private static Set<EventType> parseUserEvents(String configured) {
        if (configured == null || configured.isBlank() || "*".equals(configured.trim()) || "all".equalsIgnoreCase(configured.trim())) {
            return Set.of(EventType.values());
        }
        if ("none".equalsIgnoreCase(configured.trim())) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(name -> EventType.valueOf(name.toUpperCase(Locale.ENGLISH)))
                .collect(Collectors.toSet());
    }

    private static String resolveConfigVar(Scope config, String variableName, String defaultValue) {

        String value = defaultValue;
        if (config != null && config.get(variableName) != null) {
            value = config.get(variableName);
        } else {
            //try from env variables eg: EVENT_FORWARDER_URL:
            String envVariableName = "EVENT_FORWARDER_" + variableName.toUpperCase(Locale.ENGLISH);
            String env = System.getenv(envVariableName);
            if (env != null) {
                value = env;
            } else {
                LOGGER.trace("not found, using default {}", defaultValue);
            }
        }
        if (!"amqp_password".equals(variableName)) {
            LOGGER.info("event-forwarder configuration: {}={}", variableName, value);
        }
        return value;

    }

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public Integer getHandshakeTimeout() {
        return handshakeTimeout;
    }

    public String getHostUrl() {
        return hostUrl;
    }

    public void setHostUrl(String hostUrl) {
        this.hostUrl = hostUrl;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getVhost() {
        return vhost;
    }

    public void setVhost(String vhost) {
        this.vhost = vhost;
    }

    public Boolean getUseTls() {
        return useTls;
    }

    public void setUseTls(Boolean useTls) {
        this.useTls = useTls;
    }

    // setters and getters SSL context setting
    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public void setTrustStorePass(String trustStorePass) {
        this.trustStorePass = trustStorePass;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public void setKeyStorePass(String keyStorePass) {
        this.keyStorePass = keyStorePass;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public String getTrustStorePass() {
        return trustStorePass;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public String getKeyStorePass() {
        return keyStorePass;
    }
    //

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public Set<EventType> includedUserEvents() {
        return includedUserEvents;
    }

    public boolean includedAdminEvents() {
        return includeAdminEvents;
    }

    public int getASyncReceiverQueueMaxSize() {
        return aSyncReceiverQueueMaxSize;
    }

    public WhenFull getASyncReceiverWhenFull() {
        return aSyncReceiverWhenFull;
    }

    // 0 means unlimited
    public int getMaxSendPerSecond() {
        return maxSendPerSecond;
    }

    public boolean getSerializerJsonIsPretty() {
        return serializerJsonIsPretty;
    }

    public long[] getRetryBackoffMs() {
        return retryBackoffMs;
    }

}
