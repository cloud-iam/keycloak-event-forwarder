package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.LogTrafficListener;
import org.slf4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

import static org.slf4j.LoggerFactory.getLogger;

public class AMQPConnectionFactory {

    private static final Logger LOGGER = getLogger(AMQPConnectionFactory.class);

    private final EventForwarderConfiguration configuration;

    public AMQPConnectionFactory(EventForwarderConfiguration configuration) {
        this.configuration = configuration;
    }

    public ConnectionFactory buildAMQPConnectionFactory() {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setUsername(this.configuration.getUsername());
        connectionFactory.setPassword(this.configuration.getPassword());
        connectionFactory.setVirtualHost(this.configuration.getVhost());
        connectionFactory.setHost(this.configuration.getHostUrl());
        connectionFactory.setPort(this.configuration.getPort());
        connectionFactory.setAutomaticRecoveryEnabled(true);
        connectionFactory.setConnectionTimeout(this.configuration.getConnectionTimeout());
        connectionFactory.setHandshakeTimeout(this.configuration.getHandshakeTimeout());
        // logs every AMQP command when com.rabbitmq.client.impl.LogTrafficListener is at TRACE, free otherwise
        connectionFactory.setTrafficListener(new LogTrafficListener());
        if (this.configuration.getUseTls()) {
            configureTls(connectionFactory);
        }
        return connectionFactory;
    }

    private void configureTls(ConnectionFactory connectionFactory) {
        try {
            boolean initialized = false;
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            if (!this.configuration.getTrustStore().isEmpty()) {
                KeyStore trustStore = KeyStore.getInstance("JKS");
                trustStore.load(new FileInputStream(this.configuration.getTrustStore()), this.configuration.getTrustStorePass().toCharArray());
                tmf.init(trustStore);
                sslContext.init(null, tmf.getTrustManagers(), null);
                initialized = true;
            }

            if (!this.configuration.getKeyStore().isEmpty()) {
                char[] keyPassphrase = this.configuration.getKeyStorePass().toCharArray();
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(new FileInputStream(this.configuration.getKeyStore()), keyPassphrase);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(keyStore, keyPassphrase);
                sslContext.init(kmf.getKeyManagers(), initialized ? tmf.getTrustManagers() : null, null);
                initialized = true;
            }

            if (initialized) {
                connectionFactory.useSslProtocol(sslContext);
            } else {
                connectionFactory.useSslProtocol();
            }
        } catch (Exception e) {
            LOGGER.error("Could not use SSL protocol", e);
        }
    }

}
