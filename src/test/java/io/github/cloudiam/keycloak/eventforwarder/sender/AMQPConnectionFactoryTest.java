package io.github.cloudiam.keycloak.eventforwarder.sender;

import com.rabbitmq.client.ConnectionFactory;
import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AMQPConnectionFactoryTest {

    // generated per run: the stores live in a temp dir and nothing outside this test reads them
    private static final String STORE_PASSWORD = UUID.randomUUID().toString();

    @TempDir
    Path tempDir;

    private EventForwarderConfiguration configuration;

    @BeforeEach
    void setUp() {
        // the documented defaults, so a test only states what it deliberately changes
        this.configuration = EventForwarderConfiguration.createFromScope(null);
    }

    @Test
    void doesNotEnableTlsWhenNotAskedTo() {
        ConnectionFactory factory = build();

        assertFalse(factory.isSSL());
    }

    @Test
    void enablesTlsWithTheDefaultTrustStoreWhenNoCustomCaIsConfigured() {
        this.configuration.setUseTls(true);

        ConnectionFactory factory = build();

        // no trust store configured means the JVM default one, not "trust everything"
        assertTrue(factory.isSSL());
    }

    @Test
    void acceptsAPkcs12TrustStore() throws Exception {
        this.configuration.setUseTls(true);
        this.configuration.setTrustStore(writeEmptyStore("PKCS12", "truststore.p12").toString());
        this.configuration.setTrustStorePass(STORE_PASSWORD);

        assertDoesNotThrow(this::build);
    }

    @Test
    void acceptsAJksTrustStore() throws Exception {
        this.configuration.setUseTls(true);
        this.configuration.setTrustStore(writeEmptyStore("JKS", "truststore.jks").toString());
        this.configuration.setTrustStorePass(STORE_PASSWORD);

        assertDoesNotThrow(this::build);
    }

    @Test
    void failsWhenTheTrustStoreIsMissingRatherThanConnectingUnverified() {
        this.configuration.setUseTls(true);
        this.configuration.setTrustStore(this.tempDir.resolve("absent.p12").toString());
        this.configuration.setTrustStorePass(STORE_PASSWORD);

        IllegalStateException failure = assertThrows(IllegalStateException.class, this::build);

        assertTrue(failure.getMessage().contains("TLS"), failure.getMessage());
    }

    @Test
    void failsWhenTheTrustStorePasswordIsWrong() throws Exception {
        this.configuration.setUseTls(true);
        this.configuration.setTrustStore(writeEmptyStore("PKCS12", "truststore.p12").toString());
        this.configuration.setTrustStorePass("wrong");

        assertThrows(IllegalStateException.class, this::build);
    }

    @Test
    void failsWhenTheKeyStoreCannotBeRead() {
        this.configuration.setUseTls(true);
        this.configuration.setKeyStore(this.tempDir.resolve("absent.p12").toString());
        this.configuration.setKeyStorePass(STORE_PASSWORD);

        assertThrows(IllegalStateException.class, this::build);
    }

    @Test
    void enablesUnverifiedTlsOnlyWhenExplicitlyAskedTo() {
        this.configuration.setUseTls(true);
        this.configuration.setTlsInsecure(true);
        // a store that would fail to load is never read on this path
        this.configuration.setTrustStore(this.tempDir.resolve("absent.p12").toString());

        ConnectionFactory factory = assertDoesNotThrow(this::build);

        assertTrue(factory.isSSL());
    }

    @Test
    void ignoresTheInsecureFlagWhenTlsIsOff() {
        this.configuration.setTlsInsecure(true);

        ConnectionFactory factory = build();

        assertFalse(factory.isSSL());
    }

    private ConnectionFactory build() {
        return new AMQPConnectionFactory(this.configuration).buildAMQPConnectionFactory();
    }

    private Path writeEmptyStore(String type, String name) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, null);
        Path path = this.tempDir.resolve(name);
        try (OutputStream out = new FileOutputStream(path.toFile())) {
            store.store(out, STORE_PASSWORD.toCharArray());
        }
        return path;
    }
}
