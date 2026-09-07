package io.github.cloudiam.keycloak.eventforwarder;

import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MonitoringSinkTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MonitoringSink sink = new MonitoringSink(registry);

    @Test
    void recordsDurations() {
        this.sink.recordSerializationDuration(5);
        this.sink.recordQueuedDuration(10);
        this.sink.recordTotalSentDuration(20);
        this.sink.recordRabbitMQSentDuration(15);

        assertEquals(5, this.registry.timer("keycloak_extensions_event_forwarder_serialization_duration").totalTime(MILLISECONDS));
        assertEquals(10, this.registry.timer("keycloak_extensions_event_forwarder_queued_duration").totalTime(MILLISECONDS));
        assertEquals(20, this.registry.timer("keycloak_extensions_event_forwarder_total_sent_duration").totalTime(MILLISECONDS));
        assertEquals(15, this.registry.get("keycloak_extensions_event_forwarder_transport_sent_duration")
                .tag("transport", "rabbitmq").timer().totalTime(MILLISECONDS));
    }

    @Test
    void recordsThrottledDelayInNanoseconds() {
        this.sink.recordThrottledDelay(2_000_000); // 2ms in ns

        assertEquals(2, this.registry.timer("keycloak_extensions_event_forwarder_throttled_delay_duration").totalTime(MILLISECONDS));
    }

    @Test
    void countsRetries() {
        this.sink.countRetry();
        this.sink.countRetry();

        assertEquals(2, this.registry.counter("keycloak_extensions_event_forwarder_send_retries").count());
    }

    @Test
    void countsDroppedEvents() {
        this.sink.countDropped();

        assertEquals(1, this.registry.counter("keycloak_extensions_event_forwarder_dropped").count());
        assertEquals(0, this.registry.counter("keycloak_extensions_event_forwarder_send_retries").count());
    }

    @Test
    void gaugesQueueSizeAndCapacity() {
        var queue = new java.util.ArrayDeque<String>();
        queue.add("one");

        this.sink.registerQueueSize(queue, 64);

        assertEquals(1, this.registry.get("keycloak_extensions_event_forwarder_queue_size").gauge().value());
        assertEquals(64, this.registry.get("keycloak_extensions_event_forwarder_queue_capacity").gauge().value());
    }

    @Test
    void averagesPayloadSize() {
        this.sink.recordPayloadSize(100);
        this.sink.recordPayloadSize(300);

        assertEquals(200, this.registry.summary("keycloak_extensions_event_forwarder_payload_size").mean());
        assertEquals(2, this.registry.summary("keycloak_extensions_event_forwarder_payload_size").count());
    }

}
