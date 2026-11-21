package com.roseroots.kitchen.app;

import com.roseroots.kitchen.service.http.KitchenHttpFixture;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static com.roseroots.kitchen.service.http.KitchenHttpFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class KitchenRestNavigationTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        var started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        assertTrue(started.await(8, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    void localStaffLauncherKeepsKitchenWorkspaceUnderManagerSignIn() throws Exception {
        fx(() -> {
            var application = new KitchenApplication();
            var kitchen = new Stage();
            application.start(kitchen, Map.of("ROSE_KITCHEN_MODE", "local"));
            try {
                var shell = (KitchenShell) kitchen.getScene().getRoot();
                assertTrue(hasLabel(shell, "Local mode"));
                fire(shell, "Live Orders");
                ((ButtonBase) shell.lookup(".view-details")).fire();
                assertTrue(hasLabel(shell, "Kitchen Order Details"));
                for (var screen : Map.of("Priority Queue", "Priority Management",
                        "Stations", "Kitchen Station Queues", "Assistance", "Assistance Requests",
                        "Reviews", "Kitchen Feedback & Reviews", "History & Sync", "History & Sync")
                        .entrySet()) {
                    fire(shell, screen.getKey());
                    assertTrue(hasLabel(shell, screen.getValue()));
                }
                fire(shell, "Handoff");
                assertTrue(hasLabel(shell, "Service & Pickup Handoff"));
                fire(shell, "Manager sign-in");
                var manager = Window.getWindows().stream()
                        .filter(window -> window instanceof Stage stage && stage.getOwner() == kitchen)
                        .map(Stage.class::cast)
                        .findFirst().orElseThrow();
                try {
                    assertEquals(Modality.WINDOW_MODAL, manager.getModality());
                    var login = manager.getScene().getRoot();
                    assertTrue(login.lookupAll(".label").stream().map(Label.class::cast)
                            .anyMatch(label -> label.getText().equals("Manager sign-in")));
                    fire(login, "Sign in");
                    assertTrue(login.lookupAll(".label").stream().map(Label.class::cast)
                            .anyMatch(label -> label.getText().equals("Enter your username and password.")));
                    fire(login, "Back to Kitchen");
                    assertFalse(manager.isShowing());
                    assertSame(shell, kitchen.getScene().getRoot());
                    assertTrue(hasLabel(shell, "Service & Pickup Handoff"));
                } finally {
                    manager.close();
                }
            } finally {
                application.stop();
                kitchen.close();
            }
            return null;
        });
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
                var root = new KitchenShell(remote, null, null, remote, List.of(), key -> {}, Clock.systemUTC(), () -> {}, () -> {});
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

    private static void fire(javafx.scene.Parent root, String text) {
        root.applyCss();
        root.layout();
        var button = java.util.stream.Stream.concat(root.lookupAll(".button").stream(),
                root.lookupAll(".toggle-button").stream()).map(ButtonBase.class::cast)
                .filter(control -> control.getText().equals(text)).findFirst().orElseThrow();
        assertFalse(button.isDisabled(), text);
        button.fire();
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
