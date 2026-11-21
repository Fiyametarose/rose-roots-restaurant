package com.roseroots.kitchen.app;

import com.roseroots.kitchen.model.StaffMember;
import com.roseroots.kitchen.screens.HandoffView;
import com.roseroots.kitchen.screens.LiveBoard;
import com.roseroots.kitchen.screens.OrderDetails;
import com.roseroots.kitchen.screens.PriorityQueueView;
import com.roseroots.kitchen.service.KitchenService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

final class KitchenShell extends BorderPane {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("EEE, MMM d  •  h:mm a", Locale.ENGLISH);

    private final LiveBoard live;
    private final HandoffView handoff;
    private final OrderDetails details;
    private final PriorityQueueView priority;
    private final Label title = label("Kitchen Display Board", "title");
    private final Label wallClock = label("", "wall-clock");
    private final Timeline timer;

    KitchenShell(KitchenService service, List<StaffMember> staff, Consumer<String> selectProfile,
                 Clock clock, Runnable toggleFullScreen, Runnable openAdmin) {
        getStyleClass().add("board");
        VBox workspace = new VBox(10);
        details = new OrderDetails(service, clock, () -> showLive(workspace));
        live = new LiveBoard(service, clock, ticketId -> {
            details.open(ticketId);
            show(workspace, details, "Kitchen Order Details");
        });
        handoff = new HandoffView(service);
        priority = new PriorityQueueView(service, staff, selectProfile, clock);
        workspace.getStyleClass().add("workspace");
        HBox header = new HBox(12, title, spacer(), wallClock);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        workspace.getChildren().addAll(header, live);
        VBox.setVgrow(live, Priority.ALWAYS);
        setCenter(workspace);
        setLeft(navigation(workspace, toggleFullScreen, openAdmin));
        wallClock.setText(CLOCK.format(LocalDateTime.now(clock)));
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            wallClock.setText(CLOCK.format(LocalDateTime.now(clock)));
            Node visible = workspace.getChildren().get(1);
            if (visible == details) {
                details.tick();
            } else if (visible == priority) {
                priority.tick();
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private VBox navigation(VBox workspace, Runnable toggleFullScreen, Runnable openAdmin) {
        VBox brand = new VBox(0, label("❦", "botanical"), label("Rose & Roots", "brand-name"),
                label("R E S T A U R A N T", "brand-caption"));
        brand.getStyleClass().add("brand");
        brand.setAlignment(Pos.CENTER);
        ToggleGroup group = new ToggleGroup();
        ToggleButton liveButton = destination("Live Orders", group, () -> showLive(workspace));
        liveButton.setSelected(true);
        ToggleButton priorityButton = destination("Priority Queue", group, () -> {
            priority.tick();
            show(workspace, priority, "Priority Management");
        });
        ToggleButton handoffButton = destination("Handoff", group, () -> {
            handoff.refresh();
            show(workspace, handoff, "Service & Pickup Handoff");
        });
        Region spring = new Region();
        VBox.setVgrow(spring, Priority.ALWAYS);
        Label connection = label("Local mode — Server not connected", "connection");
        connection.setWrapText(true);
        Button fullScreen = new Button("Toggle full screen");
        fullScreen.getStyleClass().addAll("utility", "navigation-button");
        fullScreen.setMaxWidth(Double.MAX_VALUE);
        fullScreen.setOnAction(event -> toggleFullScreen.run());
        Button admin = new Button("Manager sign-in");
        admin.getStyleClass().addAll("utility", "navigation-button");
        admin.setMaxWidth(Double.MAX_VALUE);
        admin.setOnAction(event -> openAdmin.run());
        VBox menu = new VBox(6, brand, liveButton, priorityButton, pending("Stations"),
                pending("Assistance"), pending("Reviews"), handoffButton, pending("History & Sync"),
                spring, connection, fullScreen, admin);
        menu.getStyleClass().add("sidebar");
        menu.setMinWidth(145);
        menu.setPrefWidth(152);
        return menu;
    }

    private void showLive(VBox workspace) {
        live.redraw();
        show(workspace, live, "Kitchen Display Board");
    }

    private void show(VBox workspace, Node screen, String heading) {
        if (workspace.getChildren().get(1) != screen) {
            workspace.getChildren().set(1, screen);
        }
        VBox.setVgrow(screen, Priority.ALWAYS);
        title.setText(heading);
    }

    private static ToggleButton destination(String text, ToggleGroup group, Runnable action) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("navigation-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setToggleGroup(group);
        button.setOnAction(event -> {
            button.setSelected(true);
            action.run();
        });
        return button;
    }

    private static Button pending(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("navigation-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setDisable(true);
        return button;
    }

    void stop() {
        live.stop();
        timer.stop();
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
