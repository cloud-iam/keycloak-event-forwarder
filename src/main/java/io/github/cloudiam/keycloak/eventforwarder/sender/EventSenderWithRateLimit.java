package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.MonitoringSink;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import static org.slf4j.LoggerFactory.getLogger;

public class EventSenderWithRateLimit implements EventSender {

    private static final Logger LOGGER = getLogger(EventSenderWithRateLimit.class);

    private final EventSender delegate;
    private final int maxPerWindow;
    private final long windowNanos;
    private final Deque<Long> recentSends = new ArrayDeque<>();

    public EventSenderWithRateLimit(EventSender delegate, int maxSendPerSecond) {
        this(delegate, maxSendPerSecond, Duration.ofSeconds(1));
    }

    EventSenderWithRateLimit(EventSender delegate, int maxPerWindow, Duration window) {
        this.delegate = delegate;
        this.maxPerWindow = maxPerWindow;
        this.windowNanos = window.toNanos();
    }

    @Override
    public void send(InternalEvent<?> event) {
        if (!this.acquireSlot()) {
            return;
        }
        this.delegate.send(event);
    }

    private boolean acquireSlot() {
        while (true) {
            long waitNanos = reserveOrComputeWait();
            if (waitNanos == 0) {
                return true;
            }
            try {
                // sleep outside the lock so waiting threads don't block others from reserving a slot
                MonitoringSink.INSTANCE.recordThrottledDelay(waitNanos);
                LOGGER.trace("rate limited, waiting {}ns before retrying", waitNanos);
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException ie) {
                // shutdown: give up so the receiver loop can exit
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Evicts expired timestamps and, if the window has room, reserves a slot.
     * Synchronized because {@code recentSends} is shared: in synchronous mode the sender chain
     * runs on concurrent Keycloak event threads (in async mode a single worker calls it).
     *
     * @return {@code 0} when a slot has been reserved, otherwise the time in nanoseconds to wait
     * before retrying.
     */
    private synchronized long reserveOrComputeWait() {
        long now = System.nanoTime();
        while (!this.recentSends.isEmpty() && now - this.recentSends.peekFirst() >= this.windowNanos) {
            this.recentSends.pollFirst();
        }
        if (this.recentSends.size() < this.maxPerWindow) {
            this.recentSends.addLast(now);
            return 0;
        }
        return this.windowNanos - (now - this.recentSends.peekFirst());
    }

}
