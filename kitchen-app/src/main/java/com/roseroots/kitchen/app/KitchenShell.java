package com.roseroots.kitchen.app;

import com.roseroots.kitchen.model.StaffMember;
import com.roseroots.kitchen.screens.AssistanceView;
import com.roseroots.kitchen.screens.ConnectedAssistanceView;
import com.roseroots.kitchen.screens.ConnectedReviewsView;
import com.roseroots.kitchen.service.http.SpringBootKitchenService;
import com.roseroots.kitchen.screens.HandoffView;
import com.roseroots.kitchen.screens.LiveBoard;
import com.roseroots.kitchen.screens.HistoryView;
import com.roseroots.kitchen.screens.ReviewsView;
import com.roseroots.kitchen.screens.OrderDetails;
import com.roseroots.kitchen.screens.PriorityQueueView;
import com.roseroots.kitchen.screens.StationsView;
import com.roseroots.kitchen.service.AssistanceService;
import com.roseroots.kitchen.service.KitchenService;
import com.roseroots.kitchen.service.KitchenReviewService;
import com.roseroots.kitchen.service.KitchenSync;
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
    private final StationsView stations;
    private final AssistanceView assistance;
    private ConnectedAssistanceView connectedAssistance;
    private ConnectedReviewsView connectedReviews;
    private ToggleButton assistanceButton;
    private final ReviewsView reviews;
    private final HistoryView history;
    private final KitchenSync sync;
    private final Runnable unsubscribeSync;
    private final Label connection = label("", "connection");
    private Runnable backFromDetails;
    private final Label title = label("Kitchen Display Board", "title");
    private final Label wallClock = label("", "wall-clock");
    private final Timeline timer;

    KitchenShell(KitchenService service, AssistanceService assistanceService, KitchenReviewService reviewService,
                 KitchenSync sync, List<StaffMember> staff, Consumer<String> selectProfile, Clock clock,
                 Runnable toggleFullScreen, Runnable openAdmin) {
        this.sync = sync;
        getStyleClass().add("board");
        VBox workspace = new VBox(10);
        details = new OrderDetails(service, clock, () -> backFromDetails.run());
        live = new LiveBoard(service, clock, ticketId ->
                openDetails(workspace, ticketId, "Live Orders", () -> showLive(workspace)));
        stations = new StationsView(service, clock, ticketId ->
                openDetails(workspace, ticketId, "Stations", () -> showStations(workspace)));
        assistance = new AssistanceView(assistanceService);
        reviews = new ReviewsView(reviewService);
        history = new HistoryView(service, sync);
        handoff = new HandoffView(service);
        priority = new PriorityQueueView(service, staff, selectProfile, clock);
        workspace.getStyleClass().add("workspace");
        HBox header = new HBox(12, title, spacer(), wallClock);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        Label requestStatus = label("", "customer-note");
        requestStatus.setWrapText(true);
        requestStatus.managedProperty().bind(requestStatus.textProperty().isNotEmpty());
        requestStatus.visibleProperty().bind(requestStatus.managedProperty());
        VBox heading = new VBox(5, header, requestStatus);
        Button refresh = new Button("Refresh tickets");
        if (!service.local()) {
            refresh.getStyleClass().add("filter");
            refresh.setOnAction(event -> sync.refresh());
            header.getChildren().add(refresh);
        }
        workspace.getChildren().addAll(heading, live);
        VBox.setVgrow(live, Priority.ALWAYS);
        setCenter(workspace);
        setLeft(navigation(workspace, toggleFullScreen, openAdmin, service.local()));
        connection.setText(sync.status().label);
        unsubscribeSync = sync.onOrdersChanged(() -> {
            connection.setText(sync.status().label);
            requestStatus.setText(sync.message());
            refresh.setDisable(sync.busy());
            live.redraw();
            Node visible = workspace.getChildren().get(1);
            if (visible == history) {
                history.refresh();
            } else if (visible == details) {
                details.refresh();
            } else if (visible == handoff) {
                handoff.refresh();
            } else if (visible == stations) {
                stations.refresh();
            } else if (visible == priority) {
                priority.tick();
            }
        });
        wallClock.setText(CLOCK.format(LocalDateTime.now(clock)));
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            wallClock.setText(CLOCK.format(LocalDateTime.now(clock)));
            connection.setText(sync.status().label);
            Node visible = workspace.getChildren().get(1);
            if (visible == details) {
                details.tick();
            } else if (visible == priority) {
                priority.tick();
            } else if (visible == stations) {
                stations.refresh();
            } else if (visible == assistance) {
                assistance.refresh();
            } else if (visible == history) {
                history.refreshConnection();
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private VBox navigation(VBox workspace, Runnable toggleFullScreen, Runnable openAdmin, boolean local) {
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
        ToggleButton stationsButton = destination("Stations", group, () -> showStations(workspace));
        assistanceButton = destination("Assistance", group, () -> {
            if (local) {
                assistance.refresh();
                show(workspace, assistance, "Assistance Requests");
            } else if (connectedAssistance != null) {
                show(workspace, connectedAssistance, "Assistance Requests");
            } else {
                show(workspace, label("Customer assistance is unavailable from the connected server.", "empty"),
                        "Assistance Requests");
            }
        });
        ToggleButton handoffButton = destination("Handoff", group, () -> {
            handoff.refresh();
            show(workspace, handoff, "Service & Pickup Handoff");
        });
        ToggleButton reviewsButton = destination("Reviews", group, () -> {
            if (local) {
                reviews.refresh();
                show(workspace, reviews, "Kitchen Feedback & Reviews");
            } else if (connectedReviews != null) {
                connectedReviews.refresh();
                show(workspace, connectedReviews, "Kitchen Feedback & Reviews");
            } else {
                show(workspace, label("Customer reviews are unavailable from the connected server.", "empty"),
                        "Kitchen Feedback & Reviews");
            }
        });
        ToggleButton historyButton = destination("History & Sync", group, () -> {
            history.refresh();
            show(workspace, history, "History & Sync");
        });
        Region spring = new Region();
        VBox.setVgrow(spring, Priority.ALWAYS);
        connection.setWrapText(true);
        Button fullScreen = new Button("Toggle full screen");
        fullScreen.getStyleClass().addAll("utility", "navigation-button");
        fullScreen.setMaxWidth(Double.MAX_VALUE);
        fullScreen.setOnAction(event -> toggleFullScreen.run());
        Button admin = new Button("Manager sign-in");
        admin.getStyleClass().addAll("utility", "navigation-button");
        admin.setMaxWidth(Double.MAX_VALUE);
        admin.setOnAction(event -> openAdmin.run());
        VBox menu = new VBox(6, brand, liveButton, priorityButton, stationsButton,
                assistanceButton, reviewsButton, handoffButton, historyButton,
                spring, connection, fullScreen, admin);
        menu.getStyleClass().add("sidebar");
        menu.setMinWidth(145);
        menu.setPrefWidth(152);
        return menu;
    }

    private void openDetails(VBox workspace, long ticketId, String origin, Runnable back) {
        backFromDetails = back;
        details.open(ticketId, origin);
        show(workspace, details, "Kitchen Order Details");
    }

    private void showStations(VBox workspace) {
        stations.refresh();
        show(workspace, stations, "Kitchen Station Queues");
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

    void stop() {
        unsubscribeSync.run();
        if (connectedAssistance != null) connectedAssistance.stop();
        if (connectedReviews != null) connectedReviews.stop();
        sync.stop();
        live.stop();
        timer.stop();
    }

    void connectStaffWorkspaces(SpringBootKitchenService service) {
        connectedAssistance = new ConnectedAssistanceView(service,
                count -> assistanceButton.setText(count == 0 ? "Assistance" : "Assistance (" + count + ")"));
        connectedReviews = new ConnectedReviewsView(service);
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
