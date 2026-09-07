package io.github.cloudiam.keycloak.eventforwarder.receiver;

import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSender;
import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import org.slf4j.Logger;

import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.slf4j.LoggerFactory.getLogger;

public class ASyncEventReceiver implements EventReceiver, Closeable {

    private static final Logger LOGGER = getLogger(ASyncEventReceiver.class);

    private final EventSender sender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("event-forwarder-sender"));
    private final BlockingQueue<InternalEvent<?>> queue;
    private final BiConsumer<BlockingQueue<InternalEvent<?>>, InternalEvent<?>> store;

    public ASyncEventReceiver(EventSender sender, EventForwarderConfiguration configuration) {
        this.sender = sender;
        this.queue = new ArrayBlockingQueue<>(configuration.getASyncReceiverQueueMaxSize());
        this.store = switch (configuration.getASyncReceiverWhenFull()) {
            case BLOCK -> ASyncEventReceiver::putInterruptibly;
            case DROP ->
                // offer rejects silently when the queue is full
                    (internalEvents, e) -> {
                        if (!internalEvents.offer(e)) {
                            MonitoringSink.INSTANCE.countDropped();
                            LOGGER.error("queue is full, {} event has been dropped", e.id());
                        }
                    };
        };
        MonitoringSink.INSTANCE.registerQueueSize(this.queue, configuration.getASyncReceiverQueueMaxSize());
        LOGGER.info("buffering events in a queue of {} with the {} policy when full",
                configuration.getASyncReceiverQueueMaxSize(), configuration.getASyncReceiverWhenFull());
        executor.execute(this::loopAndSend);
    }

    private static void putInterruptibly(BlockingQueue<InternalEvent<?>> queue, InternalEvent<?> event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        // shutdownNow interrupts the blocked take() so loopAndSend can end,
        // then wait for the sender thread to actually die before the caller
        // tears down the resources it may still be using (rabbitmq connection)
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(3, TimeUnit.SECONDS)) {
                LOGGER.warn("event sender thread did not stop within 3s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void received(InternalEvent<?> event) {
        LOGGER.trace("queuing: {}", event.id());
        long startedAt = System.currentTimeMillis();
        this.store.accept(queue, event);
        MonitoringSink.INSTANCE.recordTimeToQueue(System.currentTimeMillis() - startedAt);
        LOGGER.trace("queued {} ({})", event.id(), queue.size());
    }

    public void loopAndSend() {
        try {
            while (true) {
                InternalEvent<?> event = this.queue.take();
                LOGGER.trace("taking {}", event.id());
                this.sender.onInternalEvent(event);
            }
        } catch (InterruptedException e) {
            // time to leave
            LOGGER.error("ending async event handling, {} event remained", this.queue.size());
        }
    }
}
