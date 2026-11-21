package com.roseroots.customer.services.api;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.websocket.OrderResynchronizer;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrderResynchronizerTest {
    @Test @Timeout(12)
    void resubscribesBeforeReadingAndRejectsOldConnectionResults() throws Exception {
        UUID session = UUID.randomUUID();
        var executor = Executors.newSingleThreadScheduledExecutor();
        var subscriptions = new LinkedBlockingQueue<TestSubscription>();
        var reads = new LinkedBlockingQueue<CompletableFuture<String>>();
        var results = new LinkedBlockingQueue<String>();
        var online = new LinkedBlockingQueue<Boolean>();
        try {
            try (var watcher = new OrderResynchronizer<>(executor, session, (changed, disconnected) -> {
                var subscription = new TestSubscription(changed, disconnected);
                subscriptions.add(subscription);
                return subscription;
            }, id -> {
                assertEquals(session, id);
                var result = new CompletableFuture<String>();
                reads.add(result);
                return result;
            }, results::add, online::add)) {
                var first = next(subscriptions);
                assertTrue(reads.isEmpty());
                first.ready().complete(null);
                var beforeDisconnect = next(reads);
                first.disconnected().run();
                assertFalse(next(online));

                var reconnected = next(subscriptions);
                beforeDisconnect.complete("stale snapshot");
                assertTrue(reads.isEmpty());
                reconnected.ready().complete(null);
                var initial = next(reads);
                reconnected.changed().run();
                initial.complete("superseded snapshot");
                next(reads).complete("orders and bill after reconnect");
                assertEquals("orders and bill after reconnect", next(results));
                assertTrue(next(online));

                reconnected.changed().run();
                next(reads).complete("live kitchen state");
                assertEquals("live kitchen state", next(results));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T next(BlockingQueue<T> queue) throws InterruptedException {
        var item = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(item, "Expected asynchronous milestone");
        return item;
    }

    private record TestSubscription(Runnable changed, Runnable disconnected,
                                    CompletableFuture<Void> ready) implements OrderResynchronizer.Subscription {
        TestSubscription(Runnable changed, Runnable disconnected) {
            this(changed, disconnected, new CompletableFuture<>());
        }

        @Override public void close() {}
    }
}
