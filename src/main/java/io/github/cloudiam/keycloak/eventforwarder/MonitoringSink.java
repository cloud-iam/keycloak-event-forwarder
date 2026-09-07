package io.github.cloudiam.keycloak.eventforwarder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

import java.util.Queue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class MonitoringSink {

    public static final MonitoringSink INSTANCE = new MonitoringSink(getGlobalRegistry());

    private static CompositeMeterRegistry getGlobalRegistry() {
        return Metrics.globalRegistry;
    }

    private final MeterRegistry registry;
    private final Timer serializationDuration;
    private final Timer queuedDuration;
    private final Timer totalSentDuration;
    private final Timer transportSentDuration;
    private final Counter retries;
    private final Counter dropped;
    private final DistributionSummary payloadSize;
    private final Timer timeToQueueDuration;
    private final Timer timeThrottledDelay;

    MonitoringSink(MeterRegistry registry) {
        this.registry = registry;
        this.serializationDuration = timer("keycloak_extensions_event_forwarder_serialization_duration", "time to serialize an event to json")
                .register(registry);
        this.timeToQueueDuration = timer("keycloak_extensions_event_forwarder_time_to_queue_duration", "time needed to store in queue")
                .register(registry);
        this.queuedDuration = timer("keycloak_extensions_event_forwarder_queued_duration", "time an event spent in the internal queue before being picked up")
                .register(registry);
        this.totalSentDuration = timer("keycloak_extensions_event_forwarder_total_sent_duration", "time to send an event, including retries and rate limiting")
                .register(registry);
        this.transportSentDuration = timer("keycloak_extensions_event_forwarder_transport_sent_duration", "time to send an event through transport layer", "transport", "rabbitmq")
                .register(registry);

        this.timeThrottledDelay = Timer.builder("keycloak_extensions_event_forwarder_throttled_delay_duration")
                .description("time lost waiting for a rate limit slot")
                .register(registry);

        this.dropped = Counter.builder("keycloak_extensions_event_forwarder_dropped")
                .description("number of dropped messages")
                .register(registry);
        this.retries = Counter.builder("keycloak_extensions_event_forwarder_send_retries")
                .description("number of send retries since startup")
                .register(registry);
        this.payloadSize = DistributionSummary.builder("keycloak_extensions_event_forwarder_payload_size")
                .description("event payload size")
                .baseUnit("bytes")
                .publishPercentiles(0.5, .75, .9, .99)
                .register(registry);
    }

    private static Timer.Builder timer(String name, String description, String... tags) {
        return Timer.builder(name)
                .description(description)
                .tags(tags)
                .publishPercentiles(0.5, .75, .9, .99);
    }

    public void registerQueueSize(final Queue<?> queue, long maximum) {
        // strongReference: micrometer only keeps a weak reference to the supplier
        // by default, the gauge would silently turn to NaN once it is collected
        Gauge.builder("keycloak_extensions_event_forwarder_queue_size", queue::size)
                .description("size of the buffer queue")
                .strongReference(true)
                .register(this.registry);
        // a constant gauge exports a single value, no count/sum/avg
        Gauge.builder("keycloak_extensions_event_forwarder_queue_capacity", () -> maximum)
                .description("maximum capacity of the buffer queue")
                .strongReference(true)
                .register(this.registry);
    }

    public void recordSerializationDuration(long durationMs) {
        this.serializationDuration.record(durationMs, MILLISECONDS);
    }

    public void recordQueuedDuration(long durationMs) {
        this.queuedDuration.record(durationMs, MILLISECONDS);
    }

    public void recordTotalSentDuration(long durationMs) {
        this.totalSentDuration.record(durationMs, MILLISECONDS);
    }

    public void recordRabbitMQSentDuration(long durationMs) {
        this.transportSentDuration.record(durationMs, MILLISECONDS);
    }

    public void countDropped() {
        this.dropped.increment();
    }

    public void countRetry() {
        this.retries.increment();
    }

    public void recordPayloadSize(int sizeInBytes) {
        this.payloadSize.record(sizeInBytes);
    }

    public void recordTimeToQueue(long l) {
        this.timeToQueueDuration.record(l, MILLISECONDS);
    }

    public void recordThrottledDelay(long delayNs) {
        this.timeThrottledDelay.record(delayNs, NANOSECONDS);
    }
}
