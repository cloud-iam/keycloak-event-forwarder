package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSender;
import io.github.cloudiam.keycloak.eventforwarder.sender.EventSenderWithRateLimit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSenderWithRateLimitTest {

    private static final InternalEvent<?> EVENT = new InternalEvent("evt-id", InternalEvent.Type.USER, "realm-name", "payload");

    private static class CountingSender implements EventSender {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void send(InternalEvent<?> event) {
            this.calls.incrementAndGet();
        }
    }

    @Test
    @Timeout(5)
    void sendsImmediatelyUnderTheLimit() {
        CountingSender delegate = new CountingSender();
        EventSenderWithRateLimit sender = new EventSenderWithRateLimit(delegate, 10, Duration.ofMillis(100));

        long startedAt = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            sender.send(EVENT);
        }

        assertEquals(5, delegate.calls.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() < 50);
    }

    @Test
    @Timeout(5)
    void blocksWhenTheLimitIsReached() {
        CountingSender delegate = new CountingSender();
        EventSenderWithRateLimit sender = new EventSenderWithRateLimit(delegate, 2, Duration.ofMillis(100));

        long startedAt = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            sender.send(EVENT);
        }

        assertEquals(6, delegate.calls.get());
        // 2 sends at t0, 2 at t0+100ms, 2 at t0+200ms
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= 190);
    }

    @Test
    @Timeout(10)
    void isThreadSafeUnderConcurrentSenders() throws InterruptedException {
        CountingSender delegate = new CountingSender();
        // window wide enough that nothing is throttled: this exercises concurrent access, not waiting
        EventSenderWithRateLimit sender = new EventSenderWithRateLimit(delegate, 10_000, Duration.ofSeconds(30));

        int threads = 8;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        sender.send(EVENT);
                    }
                } catch (Throwable ex) {
                    failures.add(ex);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown(); // release all threads at once to maximize contention
        assertTrue(done.await(8, TimeUnit.SECONDS), "threads did not finish in time");

        // with the unsynchronized deque this throws (ConcurrentModificationException / index errors)
        // or miscounts; the synchronized reservation makes it deterministic
        assertTrue(failures.isEmpty(), () -> "concurrent sends raised: " + failures);
        assertEquals(threads * perThread, delegate.calls.get());
    }

    @Test
    @Timeout(5)
    void stopsWaitingWhenInterrupted() throws InterruptedException {
        CountingSender delegate = new CountingSender();
        EventSenderWithRateLimit sender = new EventSenderWithRateLimit(delegate, 1, Duration.ofSeconds(10));

        Thread thread = new Thread(() -> {
            sender.send(EVENT);
            sender.send(EVENT); // limit reached, waits
        });
        thread.start();
        thread.interrupt();
        thread.join(2_000);

        assertEquals(Thread.State.TERMINATED, thread.getState());
        assertEquals(1, delegate.calls.get());
    }

}
