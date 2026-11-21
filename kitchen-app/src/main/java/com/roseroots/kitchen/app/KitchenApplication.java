package com.roseroots.kitchen.app;

import com.roseroots.kitchen.service.DemoTickets;
import com.roseroots.kitchen.service.InMemoryTicketRepository;
import com.roseroots.kitchen.service.LocalKitchenReviewService;
import com.roseroots.kitchen.service.LocalKitchenSync;
import com.roseroots.kitchen.service.MockKitchenService;
import com.roseroots.kitchen.service.MockAssistanceService;
import com.roseroots.kitchen.service.MockRushAuthorization;
import java.time.Clock;
import java.time.OffsetDateTime;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class KitchenApplication extends Application {
    private KitchenShell board;

    @Override
    public void start(Stage stage) {
        Clock clock = Clock.systemDefaultZone();
        OffsetDateTime now = OffsetDateTime.now(clock);
        var repository = new InMemoryTicketRepository(DemoTickets.createWithHistory(now));
        var staffSession = new MockRushAuthorization();
        board = new KitchenShell(new MockKitchenService(repository, staffSession, clock),
                MockAssistanceService.sample(clock, staffSession::currentStaff),
                LocalKitchenReviewService.sample(now), new LocalKitchenSync(),
                MockRushAuthorization.STAFF, staffSession::selectProfile, clock, () -> stage.setFullScreen(!stage.isFullScreen()));
        Scene scene = new Scene(board, 1280, 760);
        scene.getStylesheets().add(getClass().getResource("/kitchen/board.css").toExternalForm());
        stage.setTitle("Rose & Roots | Kitchen Display Board");
        stage.setMinWidth(755);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        board.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
