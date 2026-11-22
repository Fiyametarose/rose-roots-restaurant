package com.roseroots.admin;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import static com.roseroots.admin.CatalogControls.*;

final class CatalogWorkspace extends VBox {
    private final AdminApi api;
    private final AdminSection section;
    private final Consumer<JsonObject> openDish;
    private final Runnable openMenu;
    private final Runnable expired;
    private final Label message = label("", "feedback");
    private final VBox body = new VBox(14);
    private List<JsonObject> categories;
    private List<JsonObject> items;
    private String currency;
    private final Map<String, String> sectionNames = new LinkedHashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();
    private final List<JsonObject> orderedCategories = new ArrayList<>();

    CatalogWorkspace(AdminApi api, AdminSection section, JsonObject dish, Consumer<JsonObject> openDish,
                     Runnable openMenu, Runnable expired) {
        super(14);
        this.api = api;
        this.section = section;
        this.openDish = openDish;
        this.openMenu = openMenu;
        this.expired = expired;
        var heading = label(section.heading, "heading");
        message.visibleProperty().bind(message.textProperty().isNotEmpty());
        message.managedProperty().bind(message.visibleProperty());
        getChildren().addAll(heading, message, body);
        load(dish);
    }

    private void load(JsonObject dish) {
        body.setDisable(true);
        message.setText("Loading catalog…");
        record Loaded(JsonObject catalog, List<JsonObject> stations) {}
        AdminWorkspace.background(() -> new Loaded(api.catalog(), section == AdminSection.DISH
                ? api.adminRows("stations").asList().stream().map(entry -> entry.getAsJsonObject()).toList() : List.of()),
                (loaded, failure) -> {
            if (failure != null) {
                body.setDisable(false);
                body.getChildren().setAll(button("Retry", () -> load(dish)));
                explain(failure);
                return;
            }
            var data = loaded.catalog();
            currency = text(data, "currency");
            categories = rows(data, "categories");
            items = rows(data, "items");
            sectionNames.clear();
            parents.clear();
            orderedCategories.clear();
            buildTree("", "");
            message.setText("");
            body.setDisable(false);
            Node screen = switch (section) {
                case MENU -> menu();
                case CATEGORIES -> categories();
                case PRICES -> prices();
                case DISH -> new DishEditor(this, items.stream()
                        .filter(item -> text(item, "id").equals(text(dish, "id"))).findFirst().orElse(dish), loaded.stations());
                default -> throw new IllegalStateException("Unexpected catalog section");
            };
            body.getChildren().setAll(screen);
        });
    }

    private void buildTree(String parent, String prefix) {
        for (var category : categories) {
            if (text(category, "parentId").equals(parent)) {
                String id = text(category, "id");
                String path = prefix + text(category, "name");
                sectionNames.put(id, path);
                parents.put(id, parent);
                orderedCategories.add(category);
                buildTree(id, path + " / ");
            }
        }
    }

    private VBox menu() {
        var search = search("Search dishes");
        var category = categoryPicker("All categories", "");
        var cuisine = options("All cuisines", "Ethiopian", "South Sudanese", "International");
        cuisine.getSelectionModel().selectFirst();
        var status = options("All statuses", "Available", "Out of stock", "Archived");
        status.getSelectionModel().selectFirst();
        var table = table();
        table.getColumns().setAll(List.of(photoColumn(), column("Dish", 210, item -> text(item, "name")),
                column("Category", 180, item -> sectionName(text(item, "categoryId"))),
                column("Cuisine", 130, item -> cuisineName(text(item, "cuisine"))),
                column("Price", 95, this::price), availabilityColumn(120)));
        table.setFixedCellSize(52);
        Runnable filter = () -> table.getItems().setAll(items.stream().filter(item ->
                matches(item, search.getText())
                        && matchesSection(text(item, "categoryId"), selectedCategory(category))
                        && (cuisine.getSelectionModel().getSelectedIndex() == 0
                                || cuisineName(text(item, "cuisine")).equals(cuisine.getValue()))
                        && (status.getSelectionModel().getSelectedIndex() == 0 || status(item).equals(status.getValue())))
                .toList());
        watch(filter, search, category, cuisine, status);
        var add = button("Add Dish", () -> openDish.accept(null));
        var edit = button("Edit Dish", () -> openDish.accept(table.getSelectionModel().getSelectedItem()));
        var archive = button("Archive", () -> archive(table.getSelectionModel().getSelectedItem()));
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        archive.disableProperty().bind(edit.disableProperty());
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, item) ->
                archive.setText(archived(item) ? "Restore" : "Archive"));
        var controls = new FlowPane(10, 8, search, category, cuisine, status);
        var actions = new FlowPane(10, 8, add, edit, archive, button("Refresh", () -> load(null)));
        return new VBox(14, overview(items.size() + " menu items",
                categories.size() + " categories",
                items.stream().filter(item -> status(item).equals("Out of stock")).count() + " out of stock"),
                panel("Menu items", content(controls, table, actions)));
    }

    private Node categories() {
        var search = search("Search sections");
        var filter = options("All statuses", "Visible", "Hidden", "Archived");
        filter.getSelectionModel().selectFirst();
        var table = table();
        table.getColumns().setAll(List.of(column("Section", 220, row -> sectionName(text(row, "id"))),
                column("Items", 55, row -> text(row, "itemCount")), column("Order", 60, row -> text(row, "displayOrder")),
                column("Status", 85, row -> words(text(row, "status")))));
        var editor = new VBox();
        Runnable apply = () -> table.getItems().setAll(orderedCategories.stream().filter(row ->
                matchesText(sectionName(text(row, "id")), search.getText())
                && (filter.getSelectionModel().getSelectedIndex() == 0
                        || words(text(row, "status")).equals(filter.getValue()))).toList());
        watch(apply, search, filter);
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (row != null) {
                editor.getChildren().setAll(categoryEditor(row));
            }
        });
        editor.getChildren().setAll(categoryEditor(null));
        Runnable create = () -> {
            table.getSelectionModel().clearSelection();
            editor.getChildren().setAll(categoryEditor(null));
        };
        var controls = new FlowPane(10, 8, search, filter);
        var actions = new FlowPane(10, 8, button("New Section", create), button("Refresh", () -> load(null)));
        var left = panel("Menu structure", content(controls, table, actions));
        left.setPrefWidth(480);
        return new VBox(14, overview(categories.stream().filter(row -> text(row, "parentId").isEmpty()).count()
                + " top-level categories", categories.stream().filter(row -> !text(row, "parentId").isEmpty()).count()
                + " nested sections", items.size() + " menu items"),
                paired(left, editor));
    }

    private Node categoryEditor(JsonObject row) {
        String id = text(row, "id");
        var name = new TextField(text(row, "name"));
        var parent = categoryPicker("Top-level category", text(row, "parentId"));
        parent.getItems().removeIf(option -> !id.isEmpty() && matchesSection(option.id, id));
        var order = new TextField(row == null ? "10" : text(row, "displayOrder"));
        var state = options("Visible", "Hidden", "Archived");
        state.setValue(row == null ? "Visible" : words(text(row, "status")));
        var save = button("Save Section", () -> {
            try {
                var change = new JsonObject();
                change.addProperty("name", required(name.getText(), "Enter a section name."));
                String parentId = selectedCategory(parent);
                if (parentId.isEmpty()) {
                    change.add("parentId", JsonNull.INSTANCE);
                } else {
                    change.addProperty("parentId", parentId);
                }
                change.addProperty("displayOrder", integer(order.getText(), 0, Integer.MAX_VALUE, "Display order"));
                change.addProperty("status", token(state.getValue()));
                if (row == null || state.getValue().equals("Visible")
                        || text(row, "status").equals(token(state.getValue()))
                        || confirm("Change section visibility", "This also affects the visibility of its nested sections and dishes.")) {
                    save(id.isEmpty() ? "POST" : "PATCH", "categories" + suffix(id), change, () -> load(null));
                }
            } catch (IllegalArgumentException error) {
                message.setText(error.getMessage());
            }
        });
        return panel(row == null ? "Create a New Section" : "Edit Section",
                content(field("Section name", name), field("Parent category", parent),
                        field("Display order", order), field("Visibility", state),
                        label("Lower numbers appear first within the same parent.", "muted"), save));
    }

    private Node prices() {
        var search = search("Search dish or category");
        var table = table();
        table.getColumns().setAll(List.of(column("Menu item", 155, item -> text(item, "name")),
                column("Category", 130, item -> sectionName(text(item, "categoryId"))),
                column("Price", 85, this::price), availabilityColumn(115)));
        Runnable apply = () -> table.getItems().setAll(items.stream().filter(item -> matches(item, search.getText())).toList());
        watch(apply, search);
        var editor = new VBox(panel("Update Price & Availability",
                content(label("Select a dish to update its price or stock status.", "muted"))));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null) {
                editor.getChildren().setAll(tradingEditor(item));
            }
        });
        var left = panel("Menu Prices & Stock", content(new FlowPane(10, 8, search,
                button("Refresh", () -> load(null))), table));
        left.setPrefWidth(540);
        return new VBox(14, overview(items.size() + " menu items",
                items.stream().filter(item -> status(item).equals("Available")).count() + " available",
                items.stream().filter(item -> status(item).equals("Out of stock")).count() + " out of stock"),
                paired(left, editor));
    }

    private Node tradingEditor(JsonObject item) {
        var price = new TextField(text(item, "price"));
        var availability = options("Available", "Out of stock");
        availability.setValue(text(item, "availability").equals("AVAILABLE") ? "Available" : "Out of stock");
        var reason = new TextField();
        reason.setPromptText("Reason for change");
        var action = button("Save Changes", () -> {
            try {
                var change = new JsonObject();
                BigDecimal proposedPrice = money(price.getText());
                String proposedAvailability = availability.getValue().equals("Available") ? "AVAILABLE" : "SOLD_OUT";
                if (proposedPrice.compareTo(new BigDecimal(text(item, "price"))) != 0) {
                    change.addProperty("price", proposedPrice);
                }
                if (!proposedAvailability.equals(text(item, "availability"))) {
                    change.addProperty("availability", proposedAvailability);
                }
                if (change.isEmpty()) {
                    message.setText("Change the price or availability before saving.");
                    return;
                }
                change.addProperty("reason", required(reason.getText(), "Enter a reason for this change."));
                if (confirm("Update price and availability?", "Apply these changes to " + text(item, "name") + "?")) {
                    trading(item, change);
                }
            } catch (IllegalArgumentException error) {
                message.setText(error.getMessage());
            }
        });
        return panel("Update Price & Availability", content(label(text(item, "name"), "catalog-emphasis"),
                label("Current price  " + price(item), "muted"), field("Price (" + currency + ")", price),
                field("Availability", availability), field("Reason for change", reason), action));
    }

    private void archive(JsonObject item) {
        if (!confirm(archived(item) ? "Restore dish?" : "Archive dish?", text(item, "name"))) {
            return;
        }
        var payload = new JsonObject();
        payload.addProperty("archived", !archived(item));
        trading(item, payload);
    }

    private void trading(JsonObject item, JsonObject payload) {
        save("PATCH", "menu-items/" + text(item, "id") + "/trading", payload, () -> load(null));
    }

    java.awt.image.BufferedImage readPhoto(String reference) throws IOException, InterruptedException {
        return api.readPhoto(reference);
    }

    void saveDish(JsonObject dish, JsonObject payload, PhotoDraft photo) {
        String id = text(dish, "id");
        save(id.isEmpty() ? "POST" : "PATCH", "menu-items" + suffix(id), payload, photo, openMenu);
    }

    private void save(String method, String path, JsonObject payload, Runnable success) {
        save(method, path, payload, null, success);
    }

    private void save(String method, String path, JsonObject payload, PhotoDraft photo, Runnable success) {
        body.setDisable(true);
        message.setText(photo == null ? "Saving changes…" : "Uploading photo and saving dish…");
        AdminWorkspace.background(() -> {
            if (photo != null) {
                payload.addProperty("imageReference", photo.upload(api));
            }
            api.writeCatalog(method, path, payload);
            return null;
        }, (ignored, failure) -> {
            body.setDisable(false);
            if (failure == null) {
                message.setText("Changes saved.");
                if (getScene() != null) {
                    success.run();
                }
            } else {
                explain(failure);
            }
        });
    }

    void explain(Exception error) {
        if (error instanceof AdminApi.Rejected rejected) {
            switch (rejected.status) {
                case 401, 403 -> expired.run();
                case 404 -> message.setText("Catalog route or record unavailable. Check the server's catalog profile and refresh.");
                case 400, 409, 413, 415 -> message.setText(rejected.detail.isBlank() ? "Check the values and try again." : rejected.detail);
                default -> message.setText("The server could not complete this request.");
            }
        } else {
            message.setText(error instanceof IOException ? "Disconnected — the server could not be reached. Try again."
                    : "The catalog response could not be read.");
        }
    }

    ComboBox<SectionChoice> categoryPicker(String empty, String selection) {
        var picker = new ComboBox<SectionChoice>();
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.getItems().add(new SectionChoice("", empty));
        sectionNames.forEach((id, name) -> picker.getItems().add(new SectionChoice(id, name)));
        picker.getSelectionModel().select(picker.getItems().stream().filter(choice -> choice.id.equals(selection))
                .findFirst().orElse(picker.getItems().getFirst()));
        return picker;
    }

    private boolean matchesSection(String id, String ancestor) {
        if (ancestor.isEmpty()) {
            return true;
        }
        for (String current = id; current != null && !current.isEmpty(); current = parents.get(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static FlowPane overview(String... figures) {
        var strip = new FlowPane(10, 8);
        for (String figure : figures) {
            String[] parts = figure.split(" ", 2);
            var card = new VBox(3, label(parts[1], "muted"), label(parts[0], "catalog-emphasis"));
            card.getStyleClass().add("catalog-stat");
            card.setMinWidth(140);
            strip.getChildren().add(card);
        }
        return strip;
    }

    private boolean matches(JsonObject item, String query) {
        return matchesText(text(item, "name"), query) || matchesText(sectionName(text(item, "categoryId")), query);
    }

    private boolean confirm(String title, String detail) {
        var dialog = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.CANCEL, ButtonType.OK);
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        return dialog.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private TableColumn<JsonObject, JsonObject> photoColumn() {
        var column = new TableColumn<JsonObject, JsonObject>("Photo");
        column.setPrefWidth(76);
        column.setMinWidth(64);
        column.setCellValueFactory(row -> new javafx.beans.property.ReadOnlyObjectWrapper<>(row.getValue()));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(JsonObject item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : photo(item, 46));
            }
        });
        return column;
    }

    private static TableColumn<JsonObject, String> availabilityColumn(double width) {
        var column = column("Availability", width, CatalogWorkspace::status);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                var badge = label(value, "catalog-badge");
                badge.getStyleClass().add(switch (value) {
                    case "Available" -> "catalog-available";
                    case "Out of stock" -> "catalog-sold-out";
                    default -> "catalog-archived";
                });
                setGraphic(badge);
            }
        });
        return column;
    }

    Node photo(JsonObject item, int size) {
        var thumbnail = photoPreview(item, size);
        thumbnail.setMinSize(size, size);
        thumbnail.setMaxSize(size, size);
        return thumbnail;
    }

    javafx.scene.layout.StackPane photoPreview(JsonObject item, int height) {
        String url = api.imageUrl(text(item, "imageReference"));
        var fallback = label(height < 60 ? "No photo" : "No image preview", "catalog-photo");
        var imageBox = new javafx.scene.layout.StackPane(fallback);
        imageBox.setMinHeight(height);
        if (!url.isEmpty() && !text(item, "imageReference").isEmpty()) {
            var image = new Image(url, 0, height, true, true, true);
            var view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setFitHeight(height);
            view.fitWidthProperty().bind(imageBox.widthProperty());
            view.visibleProperty().bind(image.errorProperty().not().and(image.progressProperty().isEqualTo(1)));
            fallback.visibleProperty().bind(view.visibleProperty().not());
            imageBox.getChildren().add(view);
        }
        return imageBox;
    }

    String currency() { return currency; }
    void returnToMenu() { openMenu.run(); }
    void invalid(String detail) { message.setText(detail); }

    private String sectionName(String id) { return sectionNames.getOrDefault(id, "Unassigned"); }
    private String price(JsonObject item) { return currency + " " + text(item, "price"); }
    private static String status(JsonObject item) {
        return archived(item) ? "Archived" : text(item, "availability").equals("AVAILABLE") ? "Available" : "Out of stock";
    }
    private static boolean archived(JsonObject item) { return Boolean.parseBoolean(text(item, "archived")); }
    private static String suffix(String id) { return id.isEmpty() ? "" : "/" + id; }
    static String selectedCategory(ComboBox<SectionChoice> choice) {
        return choice.getValue() == null ? "" : choice.getValue().id;
    }

    static String token(String value) { return value.toUpperCase(java.util.Locale.ROOT).replace(' ', '_'); }

    static String required(String value, String error) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(error); }
        return value.strip();
    }

    static int integer(String value, int min, int max, String field) {
        try {
            int number = Integer.parseInt(value.strip());
            if (number >= min && number <= max) { return number; }
        } catch (NumberFormatException ignored) {
            // Present a field-specific instruction for invalid input.
        }
        throw new IllegalArgumentException(field + " requires a whole number from " + min + " to " + max + ".");
    }

    static BigDecimal money(String value) {
        if (!value.strip().matches("[0-9]{1,10}(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Enter a nonnegative price with up to two decimal places.");
        }
        return new BigDecimal(value.strip());
    }

    private static TextField search(String hint) {
        var field = new TextField();
        field.setPromptText(hint);
        field.setAccessibleText(hint);
        return field;
    }

    private static void watch(Runnable action, TextField search, ComboBox<?>... filters) {
        search.textProperty().addListener((obs, before, after) -> action.run());
        for (var filter : filters) {
            filter.valueProperty().addListener((obs, before, after) -> action.run());
        }
        action.run();
    }

    record SectionChoice(String id, String name) {
        @Override public String toString() { return name; }
    }
}
