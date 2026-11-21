package com.roseroots.kitchen.app;

import com.roseroots.kitchen.service.http.KitchenHttpFixture;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static com.roseroots.kitchen.service.http.KitchenHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class KitchenRestNavigationTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        var started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(8, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    void blockedHttpRefreshLeavesJavaFxResponsiveAndPreservesSelectedDetails() throws Exception {
        var reads = new AtomicInteger();
        var requestWaiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new KitchenHttpFixture(exchange -> {
            int read = reads.incrementAndGet();
            if (read == 2) {
                requestWaiting.countDown();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            reply(exchange, 200, page(ticket(read == 1 ? "RECEIVED" : "PREPARING", read, false), null));
        })) {
            var remote = fixture.service(Duration.ofSeconds(10), Platform::runLater);
            var finished = new CountDownLatch(2);
            var remove = remote.onOrdersChanged(() -> {
                if (!remote.busy()) {
                    finished.countDown();
                }
            });
            KitchenShell shell = fx(() -> {
                var root = new KitchenShell(remote, null, null, remote, List.of(), key -> {}, Clock.systemUTC(), () -> {});
                new Scene(root, 1280, 760);
                remote.refresh();
                return root;
            });
            try {
                awaitCount(finished, 1);
                fx(() -> {
                    shell.applyCss();
                    shell.layout();
                    ((ButtonBase) shell.lookup(".view-details")).fire();
                    assertTrue(hasLabel(shell, "Kitchen Order Details"));
                    assertTrue(hasLabel(shell, "Order: New"));
                    remote.refresh();
                    return null;
                });
                assertTrue(requestWaiting.await(5, TimeUnit.SECONDS));
                fx(() -> {
                    assertTrue(remote.busy());
                    assertTrue(hasLabel(shell, "Kitchen Order Details"));
                    assertTrue(hasLabel(shell, "Table 07"));
                    assertTrue(hasLabel(shell, "Loading server tickets"));
                    return null;
                });
                release.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                fx(() -> {
                    assertTrue(hasLabel(shell, "Order: Preparing"));
                    assertTrue(hasLabel(shell, "Table 07"));
                    ((ButtonBase) shell.lookup(".back-to-orders")).fire();
                    assertTrue(hasLabel(shell, "Kitchen Display Board"));
                    return null;
                });
            } finally {
                release.countDown();
                remove.run();
                fx(() -> {
                    shell.stop();
                    return null;
                });
            }
        }
    }

    private static boolean hasLabel(KitchenShell shell, String text) {
        shell.applyCss();
        shell.layout();
        return shell.lookupAll(".label").stream().map(Label.class::cast)
                .anyMatch(label -> label.getText().contains(text));
    }

    private static <T> T fx(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task);
        Platform.runLater(future);
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void awaitCount(CountDownLatch latch, long target) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (latch.getCount() > target && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertEquals(target, latch.getCount());
    }
}
