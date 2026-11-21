package com.roseroots.kitchen.app;

import com.roseroots.admin.AdminWorkspace;
import com.roseroots.kitchen.model.AssistanceRequest;
import com.roseroots.kitchen.service.AssistanceService;
import com.roseroots.kitchen.service.DemoTickets;
import com.roseroots.kitchen.service.InMemoryTicketRepository;
import com.roseroots.kitchen.service.LocalKitchenReviewService;
import com.roseroots.kitchen.service.LocalKitchenSync;
import com.roseroots.kitchen.service.MockKitchenService;
import com.roseroots.kitchen.service.MockAssistanceService;
import com.roseroots.kitchen.service.MockRushAuthorization;
import com.roseroots.kitchen.service.http.KitchenBackendConfig;
import com.roseroots.kitchen.service.http.SpringBootKitchenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class KitchenApplication extends Application {
    private KitchenShell board;

    @Override
    public void start(Stage stage) {
        start(stage, System.getenv());
    }

    void start(Stage stage, Map<String, String> environment) {
        Clock clock = Clock.systemDefaultZone();
        KitchenBackendConfig config;
        try {
            config = KitchenBackendConfig.read(environment);
        } catch (IllegalArgumentException ex) {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            Platform.exit();
            return;
        }
        if (config.mode() == KitchenBackendConfig.Mode.CONNECTED) {
            var remote = new SpringBootKitchenService(config,
                    () -> Optional.ofNullable(environment.get("ROSE_KITCHEN_STAFF_TOKEN")), Platform::runLater);
            board = new KitchenShell(remote, disconnectedAssistance(), List::of, remote,
                    List.of(), id -> {}, clock, () -> stage.setFullScreen(!stage.isFullScreen()),
                    () -> new AdminWorkspace(stage));
            remote.startAutomaticRefresh();
        } else {
            openLocal(stage, clock);
        }
        Scene scene = new Scene(board, 1280, 760);
        scene.getStylesheets().add(getClass().getResource("/kitchen/board.css").toExternalForm());
        stage.setTitle("Rose & Roots | Kitchen Display Board");
        stage.setMinWidth(755);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();
    }

    private void openLocal(Stage stage, Clock clock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var repository = new InMemoryTicketRepository(DemoTickets.createWithHistory(now));
        var staffSession = new MockRushAuthorization();
        board = new KitchenShell(new MockKitchenService(repository, staffSession, clock),
                MockAssistanceService.sample(clock, staffSession::currentStaff),
                LocalKitchenReviewService.sample(now), new LocalKitchenSync(),
                MockRushAuthorization.STAFF, staffSession::selectProfile, clock, () -> stage.setFullScreen(!stage.isFullScreen()),
                () -> new AdminWorkspace(stage));
    }

    private static AssistanceService disconnectedAssistance() {
        return new AssistanceService() {
            @Override public List<AssistanceRequest> requests() { return List.of(); }
            @Override public void acknowledge(long id) { throw new UnsupportedOperationException(); }
            @Override public void resolve(long id) { throw new UnsupportedOperationException(); }
        };
    }

    @Override
    public void stop() {
        if (board != null) {
            board.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
