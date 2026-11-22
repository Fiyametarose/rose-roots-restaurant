package com.roseroots.customer.screens;

import com.roseroots.customer.components.ChoiceBar;
import com.roseroots.customer.components.CustomerShell;
import com.roseroots.customer.components.FoodCard;
import com.roseroots.customer.components.InterfaceIcon;
import com.roseroots.customer.components.MenuFilterPane;
import com.roseroots.customer.components.MenuPhoto;
import com.roseroots.customer.models.MenuCategory;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.services.MenuSearch;
import com.roseroots.customer.services.MenuService;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.state.MenuBrowseState;
import com.roseroots.customer.utils.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public final class MenuScreen extends StackPane {
    private static final double MIN_COLUMN_PITCH = 255;
    private final MenuService menuService;
    private final RestaurantSettings settings;
    private final MenuBrowseState browse;
    private final Locale locale;
    private final Messages messages;
    private final CustomerShell shell;
    private final Consumer<String> openDish;
    private final Label heading = new Label();
    private final Label resultStatus = new Label();
    private final Label loadStatus = new Label();
    private final FlowPane titleRow = new FlowPane(16, 5);
    private final VBox subcategories = new VBox();
    private final VBox filters = new VBox();
    private final GridPane cards = new GridPane();
    private final StackPane filterOverlay = new StackPane();
    private final ScrollPane scroll;
    private final Button refresh;
    private final PauseTransition catalogRefresh = new PauseTransition(Duration.seconds(30));
    private final PauseTransition delay = new PauseTransition(Duration.millis(120));
    private List<MenuItem> results = List.of();
    private int columns;
    private long searchRevision;
    private MenuFilterPane filterPane;

    public MenuScreen(RestaurantSettings settings, CustomerState state, MenuService menuService,
                      Consumer<Locale> onLanguage, Runnable goHome, Consumer<String> openDish, Runnable goCart, Runnable goOrders) {
        this.settings = settings;
        this.menuService = menuService;
        this.openDish = openDish;
        browse = state.menu();
        locale = state.locale();
        messages = new Messages(locale);
        shell = new CustomerShell(settings, state, messages, onLanguage, goHome, goCart);
        shell.showOrdersAccess(goOrders, false);
        filterOverlay.getStyleClass().add("filter-overlay");
        filterOverlay.setAlignment(Pos.CENTER);
        if (messages.language().equals("am")) {
            filterOverlay.getStyleClass().add("ethiopic");
        }
        filterOverlay.setVisible(false);
        shell.disableProperty().bind(filterOverlay.visibleProperty());
        getChildren().addAll(shell, filterOverlay);

        heading.getStyleClass().add("catalog-heading");
        heading.setWrapText(true);
        refresh = CustomerShell.action(messages.text("catalog.refresh"), false, this::loadCatalog);
        refresh.setId("refresh-catalog");
        titleRow.getChildren().addAll(heading, subcategories);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getStyleClass().add("catalog-title-row");
        resultStatus.setId("menu-results");
        resultStatus.getStyleClass().add("catalog-muted");
        resultStatus.setWrapText(true);
        refresh.getStyleClass().add("catalog-refresh");
        var statusRow = new FlowPane(11, 3, resultStatus, refresh);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getStyleClass().add("catalog-status-row");
        loadStatus.getStyleClass().add("catalog-muted");
        loadStatus.setWrapText(true);
        loadStatus.managedProperty().bind(loadStatus.visibleProperty());
        loadStatus.setVisible(false);
        cards.getStyleClass().add("food-grid");

        var content = new VBox(6, titleRow, filters, loadStatus, statusRow, cards);
        content.getStyleClass().add("catalog-content");
        scroll = new ScrollPane(content);
        scroll.getStyleClass().add("catalog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.viewportBoundsProperty().addListener((property, previous, next) -> {
            layoutCards(false);
            positionCuisines();
        });
        shell.showContent(scroll);

        delay.setOnFinished(event -> search());
        buildFilters();
        refreshCategories();
        if (browse.catalog() != null) showCatalog(true);
        catalogRefresh.setOnFinished(event -> loadCatalog(false));
        sceneProperty().addListener((property, previous, next) -> {
            if (next == null) catalogRefresh.stop();
            else if (browse.catalog() == null || menuService.refreshWhileVisible()) loadCatalog(browse.catalog() == null);
        });
    }

    private void buildFilters() {
        if (filterPane != null) {
            filterPane.positionCuisines(titleRow, false);
        }
        filterPane = new MenuFilterPane(messages, browse.filters(), updated -> {
            browse.setFilters(updated);
            scroll.setVvalue(0);
            searchRevision++;
            delay.playFromStart();
        }, () -> {
            browse.resetFilters();
            scroll.setVvalue(0);
            buildFilters();
            refreshCategories();
            search();
        }, filterOverlay);
        filters.getChildren().setAll(filterPane);
        positionCuisines();
    }

    private void positionCuisines() {
        if (filterPane != null) {
            filterPane.positionCuisines(titleRow,
                    !subcategories.isManaged() && scroll.getViewportBounds().getWidth() >= 1020);
        }
    }

    private void loadCatalog() {
        loadCatalog(true);
    }

    private void loadCatalog(boolean interactive) {
        if (refresh.isDisable()) return;
        catalogRefresh.stop();
        refresh.setDisable(true);
        if (interactive) {
            loadStatus.setText(messages.text("catalog.loading"));
            loadStatus.setVisible(true);
        }
        CompletableFuture.completedFuture(menuService).thenCompose(MenuService::loadCatalog)
                .whenComplete((catalog, error) -> Platform.runLater(() -> {
                    refresh.setDisable(false);
                    if (getScene() == null) return;
                    if (menuService.refreshWhileVisible()) catalogRefresh.playFromStart();
                    if (error != null) {
                        if (interactive) loadStatus.setText(messages.text(browse.catalog() == null
                                ? "menu.unavailable" : "catalog.refreshFailed"));
                        return;
                    }
                    loadStatus.setVisible(false);
                    if (!interactive && catalog.equals(browse.catalog())) return;
                    if (!interactive) browse.rememberScroll(scroll.getVvalue());
                    MenuPhoto.clearCache();
                    browse.setCatalog(catalog);
                    showCatalog(!interactive);
                }));
    }

    private void showCatalog(boolean restorePosition) {
        refreshCategories();
        search(restorePosition);
    }

    private void refreshCategories() {
        var catalog = browse.catalog();
        var category = catalog == null ? null : catalog.category(browse.categoryId());
        heading.setText(category == null ? messages.text("menu.heading") : category.name().in(locale));
        subcategories.getChildren().clear();
        subcategories.setManaged(false);
        positionCuisines();
        if (catalog == null) {
            return;
        }
        shell.displayCategories(catalog, locale, id -> {
            browse.selectCategory(id);
            scroll.setVvalue(0);
            refreshCategories();
            search();
        });
        if (category == null) {
            return;
        }
        var children = catalog.childrenOf(category.id());
        if (children.isEmpty()) {
            return;
        }
        var options = new ArrayList<String>();
        options.add("");
        children.stream().map(MenuCategory::id).forEach(options::add);
        var subcategory = browse.subcategoryId() == null ? "" : browse.subcategoryId();
        var tabs = new ChoiceBar<>(options, subcategory,
                id -> id.isEmpty() ? messages.text("catalog.allSubcategories") : catalog.category(id).name().in(locale),
                id -> {
                    browse.selectSubcategory(id.isEmpty() ? null : id);
                    scroll.setVvalue(0);
                    search();
                });
        tabs.setId("subcategory-choices");
        tabs.prefWrapLengthProperty().bind(Bindings.createDoubleBinding(() ->
                Math.clamp(scroll.getViewportBounds().getWidth() - 40, 165, 515),
                scroll.viewportBoundsProperty()));
        for (int i = 0; i < options.size(); i++) {
            var tab = (ToggleButton) tabs.getChildren().get(i);
            String id = options.get(i);
            tab.setId("subcategory-" + id);
            if (!id.isEmpty()) {
                var icon = id.equals("hot-drinks")
                        ? InterfaceIcon.Kind.STARTER : InterfaceIcon.Kind.DRINK;
                tab.setGraphic(new InterfaceIcon(icon));
            }
        }
        tabs.getStyleClass().add("subcategory-tabs");
        subcategories.getChildren().add(tabs);
        subcategories.setManaged(true);
        positionCuisines();
    }

    private void search() {
        search(false);
    }

    private void search(boolean restorePosition) {
        var catalog = browse.catalog();
        if (catalog == null) {
            return;
        }
        delay.stop();
        long revision = ++searchRevision;
        String category = browse.subcategoryId() == null ? browse.categoryId() : browse.subcategoryId();
        var requestedFilters = browse.filters();
        CompletableFuture.supplyAsync(() -> MenuSearch.search(catalog, category, requestedFilters, locale))
                .whenComplete((matches, error) -> Platform.runLater(() -> {
                    if (revision != searchRevision || getScene() == null) {
                        return;
                    }
                    if (error != null) {
                        resultStatus.setText(messages.text("catalog.searchFailed"));
                        return;
                    }
                    results = matches;
                    resultStatus.setText(results.isEmpty() ? messages.text("catalog.empty")
                            : java.text.NumberFormat.getIntegerInstance(locale).format(results.size()) + " "
                            + messages.text(results.size() == 1 ? "catalog.result" : "catalog.results"));
                    layoutCards(true);
                    if (restorePosition) {
                        Platform.runLater(() -> scroll.setVvalue(browse.scrollPosition()));
                    }
                }));
    }

    private void layoutCards(boolean rebuild) {
        double width = scroll.getViewportBounds().getWidth();
        int newColumns = Math.clamp((int) ((width - 30) / MIN_COLUMN_PITCH), 1, 3);
        if (!rebuild && newColumns == columns) {
            return;
        }
        columns = newColumns;
        cards.getChildren().clear();
        cards.getColumnConstraints().clear();
        for (int column = 0; column < columns; column++) {
            var constraint = new ColumnConstraints(0, 0, Double.MAX_VALUE);
            constraint.setPercentWidth(100.0 / columns);
            constraint.setHgrow(Priority.ALWAYS);
            cards.getColumnConstraints().add(constraint);
        }
        for (int index = 0; index < results.size(); index++) {
            var item = results.get(index);
            var card = new FoodCard(item, locale, settings.currency(), messages, () -> {
                browse.rememberScroll(scroll.getVvalue());
                openDish.accept(item.id());
            });
            cards.add(card, index % columns, index / columns);
        }
    }
}
