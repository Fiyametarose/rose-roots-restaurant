package com.roseroots.customer.screens;

import com.roseroots.customer.components.BrandHeader;
import com.roseroots.customer.components.ChoiceBar;
import com.roseroots.customer.components.InterfaceIcon;
import com.roseroots.customer.components.ServiceChoiceButton;
import com.roseroots.customer.components.ServiceIcon;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.services.RestaurantSettings;
import com.roseroots.customer.state.CustomerState;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Labeled;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class WelcomeScreen extends BorderPane {
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    private final RestaurantSettings settings;
    private final Label heading = new Label();
    private final Label tagline = new Label();
    private final Label languageLabel = new Label();
    private final ServiceChoiceButton dineIn;
    private final ServiceChoiceButton takeaway;
    private final javafx.scene.control.Button recovery = new javafx.scene.control.Button();
    private final Label fallback = new Label();

    public WelcomeScreen(RestaurantSettings settings, CustomerState state,
                         Consumer<ServiceMode> onServiceSelected) {
        this.settings = settings;
        getStyleClass().add("welcome-screen");
        widthProperty().addListener((property, previous, current) ->
                pseudoClassStateChanged(COMPACT, current.doubleValue() < 670));
        var hero = image("welcome-hero");
        hero.fitWidthProperty().bind(widthProperty().multiply(.295));
        hero.fitHeightProperty().bind(heightProperty());
        hero.fitWidthProperty().addListener(observable -> cropHero(hero));
        hero.fitHeightProperty().addListener(observable -> cropHero(hero));
        hero.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(860));
        hero.managedProperty().bind(hero.visibleProperty());
        setLeft(hero);

        heading.getStyleClass().add("welcome-heading");
        heading.setWrapText(true);
        heading.setMinHeight(Region.USE_PREF_SIZE);
        heading.setAlignment(Pos.CENTER);
        tagline.getStyleClass().add("welcome-tagline");
        tagline.setWrapText(true);
        tagline.setMinHeight(Region.USE_PREF_SIZE);
        tagline.setMaxWidth(570);
        tagline.setAlignment(Pos.CENTER);
        languageLabel.getStyleClass().add("language-heading");
        var divider = new Region();
        divider.getStyleClass().add("welcome-divider");

        dineIn = new ServiceChoiceButton(new ServiceIcon(ServiceMode.DINE_IN), true,
                () -> onServiceSelected.accept(ServiceMode.DINE_IN));
        takeaway = new ServiceChoiceButton(new ServiceIcon(ServiceMode.TAKEAWAY), false,
                () -> onServiceSelected.accept(ServiceMode.TAKEAWAY));
        dineIn.setId("dine-in");
        takeaway.setId("takeaway");
        var serviceOptions = new FlowPane(dineIn, takeaway);
        serviceOptions.setAlignment(Pos.CENTER);
        serviceOptions.getStyleClass().add("service-options");

        var languages = new ChoiceBar<>(settings.languages(), state.locale(), MenuText::languageName,
                locale -> {
                    state.setLocale(locale);
                    updateCopy(locale);
                });
        languages.getChildren().stream()
                .map(Labeled.class::cast)
                .filter(choice -> choice.getText().equals("አማርኛ"))
                .forEach(choice -> choice.getStyleClass().add("ethiopic-choice"));
        fallback.getStyleClass().add("language-fallback");
        fallback.managedProperty().bind(fallback.visibleProperty());
        updateCopy(state.locale());

        var leftRule = new Region();
        var rightRule = new Region();
        leftRule.getStyleClass().add("welcome-language-rule");
        rightRule.getStyleClass().add("welcome-language-rule");
        var languageHeading = new HBox(12, leftRule, new InterfaceIcon(InterfaceIcon.Kind.GLOBE),
                languageLabel, rightRule);
        languageHeading.setAlignment(Pos.CENTER);
        languageHeading.getStyleClass().add("welcome-language-heading");
        var form = new VBox(new BrandHeader(settings), heading, divider, tagline,
                serviceOptions, languageHeading, languages, fallback, recovery);
        recovery.managedProperty().bind(recovery.visibleProperty());
        recovery.setVisible(false);
        form.setAlignment(Pos.CENTER);
        form.getStyleClass().add("welcome-content");
        settings.observeWhileAttached(this, () -> tagline.setText(settings.tagline(state.locale())));

        var upper = image("botanical-upper");
        var lower = image("botanical-lower");
        var lowerArt = lower.getImage();
        lower.setViewport(new Rectangle2D(0, 4, lowerArt.getWidth(), lowerArt.getHeight() - 4));
        upper.setFitWidth(205);
        lower.setFitWidth(163);
        upper.setPreserveRatio(true);
        lower.setPreserveRatio(true);
        upper.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(1024));
        lower.visibleProperty().bind(upper.visibleProperty());
        StackPane.setAlignment(upper, Pos.TOP_RIGHT);
        StackPane.setAlignment(lower, Pos.BOTTOM_RIGHT);
        var scroll = new ScrollPane(form);
        scroll.setFitToHeight(true);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setCenter(new StackPane(scroll, upper, lower));
    }

    public void enableReceiptRecovery(Runnable open) {
        recovery.setVisible(true);
        recovery.getStyleClass().add("secondary-button");
        recovery.setOnAction(event -> open.run());
    }

    private static void cropHero(ImageView hero) {
        if (hero.getFitWidth() <= 0 || hero.getFitHeight() <= 0) {
            return;
        }
        var image = hero.getImage();
        double aspect = hero.getFitWidth() / hero.getFitHeight();
        double width = Math.min(image.getWidth(), image.getHeight() * aspect);
        double height = Math.min(image.getHeight(), image.getWidth() / aspect);
        hero.setViewport(new Rectangle2D(image.getWidth() - width,
                (image.getHeight() - height) / 2, width, height));
    }

    private void updateCopy(Locale locale) {
        var messages = new Messages(locale);
        messages.applyDirection(this);
        recovery.setText(messages.text("recovery.title"));
        getStyleClass().remove("ethiopic");
        if (messages.language().equals("am")) {
            getStyleClass().add("ethiopic");
        }
        heading.setText(messages.text("welcome.heading"));
        tagline.setText(settings.tagline(locale));
        languageLabel.setText(messages.text("welcome.language"));
        dineIn.setCopy(messages.text("service.dineIn"), messages.text("service.dineIn.description"));
        takeaway.setCopy(messages.text("service.takeaway"), messages.text("service.takeaway.description"));
        fallback.setText(messages.text("welcome.language.fallback"));
        fallback.setVisible(!locale.getLanguage().equals(messages.language()));
    }

    private static ImageView image(String name) {
        var url = Objects.requireNonNull(WelcomeScreen.class.getResource("/images/" + name + ".png"));
        var view = new ImageView(new Image(url.toExternalForm()));
        view.setMouseTransparent(true);
        return view;
    }
}
