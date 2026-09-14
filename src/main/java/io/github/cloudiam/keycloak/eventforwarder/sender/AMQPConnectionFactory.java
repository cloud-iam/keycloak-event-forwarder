package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.LogTrafficListener;
import org.slf4j.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Locale;

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

    /**
     * Enables TLS towards the broker, verifying its certificate and that the certificate belongs to
     * the host we dialed. A TLS setup that cannot be honoured throws: the alternative is forwarding
     * authentication events over a channel an attacker can read, which is worse than not starting.
     */
    private void configureTls(ConnectionFactory connectionFactory) {
        if (this.configuration.getTlsInsecure()) {
            configureUnverifiedTls(connectionFactory);
            return;
        }
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            // null trust managers means the JVM default trust store, so a broker with a publicly
            // trusted certificate needs no configuration at all
            sslContext.init(keyManagers(), trustManagers(), null);
            connectionFactory.useSslProtocol(sslContext);
            // the client does not check the certificate belongs to the host it dialed unless asked
            connectionFactory.enableHostnameVerification();
        } catch (Exception e) {
            throw new IllegalStateException("could not enable TLS towards the AMQP broker: " + e.getMessage(), e);
        }
    }

    private void configureUnverifiedTls(ConnectionFactory connectionFactory) {
        LOGGER.warn("EVENT_FORWARDER_AMQP_TLS_INSECURE is on: the broker certificate is accepted without"
                + " verification, so the connection is encrypted but open to interception. Use it only to reach"
                + " a test broker, and point EVENT_FORWARDER_AMQP_TRUST_STORE at your CA instead.");
        try {
            connectionFactory.useSslProtocol();
        } catch (Exception e) {
            throw new IllegalStateException("could not enable TLS towards the AMQP broker: " + e.getMessage(), e);
        }
    }

    /**
     * @return the trust managers for a custom CA, or {@code null} to keep the JVM default trust store
     */
    private TrustManager[] trustManagers() throws Exception {
        if (this.configuration.getTrustStore().isEmpty()) {
            return null;
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(load(this.configuration.getTrustStore(), this.configuration.getTrustStorePass()));
        return factory.getTrustManagers();
    }

    /**
     * @return the key managers for mutual TLS, or {@code null} when no client key store is configured
     */
    private KeyManager[] keyManagers() throws Exception {
        if (this.configuration.getKeyStore().isEmpty()) {
            return null;
        }
        char[] passphrase = this.configuration.getKeyStorePass().toCharArray();
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(load(this.configuration.getKeyStore(), this.configuration.getKeyStorePass()), passphrase);
        return factory.getKeyManagers();
    }

    private static KeyStore load(String path, String password) throws Exception {
        KeyStore store = KeyStore.getInstance(storeTypeOf(path));
        try (InputStream in = new FileInputStream(path)) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    /**
     * Both formats are accepted and the type follows the file extension, so a store named {@code .p12}
     * loads as PKCS12 instead of failing as a malformed JKS.
     */
    private static String storeTypeOf(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
    }

}
