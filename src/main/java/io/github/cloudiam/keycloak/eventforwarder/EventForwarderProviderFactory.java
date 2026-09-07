package io.github.cloudiam.keycloak.eventforwarder;

import io.github.cloudiam.keycloak.eventforwarder.receiver.ASyncEventReceiver;
import io.github.cloudiam.keycloak.eventforwarder.receiver.EventReceiver;
import io.github.cloudiam.keycloak.eventforwarder.receiver.SyncEventReceiver;
import io.github.cloudiam.keycloak.eventforwarder.sender.AMQPConnectionFactory;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSender;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSenderToRabbitMq;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSenderWithRateLimit;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSenderWithRetry;
import io.github.cloudiam.keycloak.eventforwarder.serializer.EventSerializer;
import io.github.cloudiam.keycloak.eventforwarder.serializer.JsonEventSerializer;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.LogTrafficListener;
import org.keycloak.Config.Scope;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.slf4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

import static org.slf4j.LoggerFactory.getLogger;

public class EventForwarderProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOGGER = getLogger(EventForwarderProviderFactory.class);

    private EventReceiver receiver;
    private EventSenderToRabbitMq rabbitMqSender;
    private EventPublisher publisher;

    @Override
    public void init(Scope config) {
        final EventForwarderConfiguration configuration = EventForwarderConfiguration.createFromScope(config);
        final EventSerializer<String> serializer = new JsonEventSerializer(configuration.getSerializerJsonIsPretty());

        // select the forwarding transport; only AMQP is implemented today
        final EventSender baseSender = switch (configuration.getTransport()) {
            case AMQP -> {
                this.rabbitMqSender = new EventSenderToRabbitMq(configuration, new AMQPConnectionFactory(configuration).buildAMQPConnectionFactory(), serializer);
                yield this.rabbitMqSender;
            }
        };
        // a max send per second of 0 disables the rate limiting
        EventSender sender = configuration.getMaxSendPerSecond() == 0
                ? baseSender
                : new EventSenderWithRateLimit(baseSender, configuration.getMaxSendPerSecond());
        sender = new EventSenderWithRetry(sender, configuration.getRetryBackoffMs());
        // a queue max size of 0 disables the buffering, events are sent synchronously
        this.receiver = configuration.getASyncReceiverQueueMaxSize() == 0
                ? new SyncEventReceiver(sender)
                : new ASyncEventReceiver(sender, configuration);
        this.publisher = new EventPublisher(configuration, this.receiver);
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new EventCaptor(session, this.publisher);
    }

    /**
     * Shared filtering/publication rules, also used by the replay endpoint.
     */
    public EventPublisher getPublisher() {
        return this.publisher;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {
        LOGGER.info("event-forwarder ending...");
        if (this.receiver instanceof ASyncEventReceiver async) {
            async.close();
        }
        if (this.rabbitMqSender != null) {
            this.rabbitMqSender.close();
        }
    }

    @Override
    public String getId() {
        return "event-forwarder";
    }


}
