package com.roseroots.customer.components;

import com.roseroots.customer.models.MenuItem.Allergen;
import com.roseroots.customer.models.MenuItem.Availability;
import com.roseroots.customer.models.MenuItem.Cuisine;
import com.roseroots.customer.models.MenuItem.Diet;
import com.roseroots.customer.models.MenuItem.Highlight;
import com.roseroots.customer.models.MenuItem.Spice;
import com.roseroots.customer.services.MenuSearch.Filters;
import com.roseroots.customer.services.MenuSearch.Sort;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

public final class MenuFilterPane extends VBox {
    private static final PseudoClass FILTERED = PseudoClass.getPseudoClass("filtered");
    private final TextField search = new TextField();
    private final ThemedComboBox<Sort> sort = new ThemedComboBox<>();
    private final List<ToggleButton> cuisine = new ArrayList<>();
    private final List<CheckBox> diets = new ArrayList<>();
    private final List<CheckBox> allergens = new ArrayList<>();
    private final List<CheckBox> spices = new ArrayList<>();
    private final List<CheckBox> availability = new ArrayList<>();
    private final List<CheckBox> highlights = new ArrayList<>();
    private final FlowPane cuisines = new FlowPane(8, 6);
    private Filters applied;

    public MenuFilterPane(Messages messages, Filters initial, Consumer<Filters> onChanged,
                          Runnable reset, StackPane overlay) {
        getStyleClass().add("filter-pane");
        applied = initial;

        search.setId("menu-search");
        search.setPromptText(messages.text("catalog.search"));
        search.setAccessibleText(messages.text("catalog.search"));
        search.setText(initial.query());
        var searchIcon = new InterfaceIcon(InterfaceIcon.Kind.SEARCH);
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 13));
        var searchBox = new StackPane(search, searchIcon);
        searchBox.getStyleClass().add("catalog-search");
        sort.setId("menu-sort");
        sort.getItems().setAll(Sort.values());
        sort.setAccessibleText(messages.text("catalog.sort"));
        sort.setConverter(new StringConverter<>() {
            @Override
            public String toString(Sort value) {
                return value == null ? "" : MenuText.label(messages, value);
            }

            @Override
            public Sort fromString(String text) {
                return sort.getValue();
            }
        });
        sort.setValue(initial.sort());
        var expand = new ToggleButton(messages.text("catalog.filters"));
        expand.setId("advanced-filters");
        expand.getStyleClass().add("catalog-filter-toggle");
        var resetButton = CustomerShell.action(messages.text("catalog.reset"), false, reset);
        resetButton.setId("reset-filters");
        var toolbar = new FlowPane(8, 6, searchBox, sort, expand, resetButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Runnable publish = () -> onChanged.accept(currentFilters());
        cuisines.setAlignment(Pos.CENTER_LEFT);
        cuisines.setPrefWrapLength(680);
        cuisines.getStyleClass().add("cuisine-filters");
        var all = new ToggleButton(messages.text("catalog.allCuisines"));
        all.setSelected(initial.cuisines().isEmpty());
        all.setOnAction(event -> {
            cuisine.forEach(button -> button.setSelected(false));
            all.setSelected(true);
            publish.run();
        });
        cuisines.getChildren().add(all);
        for (Cuisine value : Cuisine.values()) {
            var chip = new ToggleButton(MenuText.label(messages, value));
            chip.setId("filter-" + value.name().toLowerCase(java.util.Locale.ROOT));
            chip.setUserData(value);
            chip.setSelected(initial.cuisines().contains(value));
            chip.setOnAction(event -> {
                all.setSelected(cuisine.stream().noneMatch(ToggleButton::isSelected));
                publish.run();
            });
            cuisine.add(chip);
            cuisines.getChildren().add(chip);
        }

        var logic = new Label(messages.text("catalog.filterLogic"));
        logic.setWrapText(true);
        logic.getStyleClass().add("catalog-muted");
        var advanced = new VBox(7,
                group(messages, "catalog.dietary", Diet.values(), diets),
                group(messages, "catalog.exclude", Allergen.values(), allergens),
                group(messages, "catalog.spice", Spice.values(), spices),
                group(messages, "catalog.availability", Availability.values(), availability),
                group(messages, "catalog.highlights", Highlight.values(), highlights),
                logic);
        advanced.getStyleClass().add("advanced-filter-content");
        var vegetarian = new CheckBox(MenuText.label(messages, Diet.VEGETARIAN));
        vegetarian.setGraphic(new InterfaceIcon(InterfaceIcon.Kind.LEAF));
        vegetarian.getStyleClass().add("diet-quick-filter");
        vegetarian.setId("quick-vegetarian");
        vegetarian.setSelected(initial.diets().contains(Diet.VEGETARIAN));
        vegetarian.setOnAction(event -> {
            restoreAdvanced(applied);
            diets.getFirst().setSelected(vegetarian.isSelected());
            applied = stagedFilters();
            updateFilterCount(expand, messages);
            publish.run();
        });
        cuisines.getChildren().add(vegetarian);
        var drawerTitle = new Label(messages.text("catalog.advancedFilters"));
        drawerTitle.getStyleClass().add("dish-panel-title");
        var close = CustomerShell.action(messages.text("catalog.applyFilters"), true, () -> {
            applied = stagedFilters();
            vegetarian.setSelected(applied.diets().contains(Diet.VEGETARIAN));
            updateFilterCount(expand, messages);
            publish.run();
            expand.setSelected(false);
        });
        close.setId("close-filters");
        var drawerReset = CustomerShell.action(messages.text("catalog.reset"), false,
                () -> restoreAdvanced(Filters.defaults()));
        drawerReset.setId("reset-advanced-filters");
        var cancel = CustomerShell.action(messages.text("catalog.cancelFilters"), false,
                () -> expand.setSelected(false));
        var actions = new FlowPane(9, 6, drawerReset, cancel, close);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("filter-dialog-actions");
        var scroller = new ScrollPane(advanced);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var drawer = new BorderPane(scroller, drawerTitle, null, actions, null);
        drawer.getStyleClass().add("advanced-filter-panel");
        drawer.setMaxWidth(590);
        drawer.setMaxHeight(610);
        overlay.setOnMouseClicked(event -> {
            if (event.getTarget() == overlay) {
                expand.setSelected(false);
            }
        });
        drawer.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                expand.setSelected(false);
                event.consume();
            }
        });
        expand.selectedProperty().addListener((property, previous, opened) -> {
            if (opened) {
                restoreAdvanced(applied);
                scroller.setVvalue(0);
                overlay.getChildren().setAll(drawer);
                overlay.setVisible(true);
                close.requestFocus();
            } else {
                overlay.setVisible(false);
                overlay.getChildren().clear();
                expand.requestFocus();
            }
        });
        updateFilterCount(expand, messages);
        search.textProperty().addListener(observable -> publish.run());
        sort.valueProperty().addListener(observable -> publish.run());
        getChildren().addAll(cuisines, toolbar);
    }

    public void positionCuisines(FlowPane titleRow, boolean inline) {
        if (inline && cuisines.getParent() != titleRow) {
            getChildren().remove(cuisines);
            cuisines.setPrefWidth(625);
            titleRow.getChildren().add(cuisines);
        } else if (!inline && cuisines.getParent() == titleRow) {
            titleRow.getChildren().remove(cuisines);
            cuisines.setPrefWidth(Region.USE_COMPUTED_SIZE);
            getChildren().addFirst(cuisines);
        }
    }

    private Filters currentFilters() {
        Set<Cuisine> cuisines = cuisine.stream().filter(ToggleButton::isSelected)
                .map(button -> (Cuisine) button.getUserData()).collect(Collectors.toSet());
        return new Filters(search.getText(), cuisines, applied.diets(), applied.excludedAllergens(),
                applied.spices(), applied.availability(), applied.highlights(), sort.getValue());
    }

    private Filters stagedFilters() {
        return new Filters(search.getText(), Set.of(), selected(diets, Diet.class),
                selected(allergens, Allergen.class), selected(spices, Spice.class),
                selected(availability, Availability.class), selected(highlights, Highlight.class), sort.getValue());
    }

    private void restoreAdvanced(Filters filters) {
        setSelected(diets, filters.diets());
        setSelected(allergens, filters.excludedAllergens());
        setSelected(spices, filters.spices());
        setSelected(availability, filters.availability());
        setSelected(highlights, filters.highlights());
    }

    private void updateFilterCount(ToggleButton button, Messages messages) {
        int count = applied.diets().size() + applied.excludedAllergens().size() + applied.spices().size()
                + applied.availability().size() + applied.highlights().size();
        button.setText(messages.text("catalog.filters") + (count == 0 ? "" : " (" + count + ")"));
        button.pseudoClassStateChanged(FILTERED, count > 0);
    }

    private static void setSelected(List<CheckBox> boxes, Set<?> values) {
        boxes.forEach(box -> box.setSelected(values.contains(box.getUserData())));
    }

    private static <E extends Enum<E>> Set<E> selected(List<CheckBox> boxes, Class<E> type) {
        return boxes.stream().filter(CheckBox::isSelected).map(box -> type.cast(box.getUserData()))
                .collect(Collectors.toSet());
    }

    private static <E extends Enum<E>> VBox group(Messages messages, String key, E[] values,
            List<CheckBox> boxes) {
        var title = new Label(messages.text(key));
        title.getStyleClass().add("filter-group-label");
        var row = new FlowPane(7, 2);
        for (E value : values) {
            var box = new CheckBox(MenuText.label(messages, value));
            box.setId("filter-" + value.name().toLowerCase(java.util.Locale.ROOT));
            box.setUserData(value);
            boxes.add(box);
            row.getChildren().add(box);
        }
        return new VBox(3, title, row);
    }
}
