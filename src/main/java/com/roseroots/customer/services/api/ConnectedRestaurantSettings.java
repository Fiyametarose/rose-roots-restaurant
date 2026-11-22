package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
import com.roseroots.customer.models.RestaurantBrand;
import com.roseroots.customer.services.RestaurantSettings;
import java.net.URI;
import java.net.URL;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyObjectWrapper;

public final class ConnectedRestaurantSettings implements RestaurantSettings {
    private record Snapshot(RestaurantBrand brand, String tagline, String themeCss) {}
    private final RestaurantSettings fallback;
    private final SpringBootOrderService rest;
    private final BackendAddress address;
    private final ReadOnlyObjectWrapper<Snapshot> current = new ReadOnlyObjectWrapper<>();
    private boolean busy;
    private boolean closed;

    public ConnectedRestaurantSettings(RestaurantSettings fallback, SpringBootOrderService rest, BackendAddress address) {
        this.fallback = fallback;
        this.rest = rest;
        this.address = address;
    }

    @Override public RestaurantBrand brand() { return current.get() == null ? fallback.brand() : current.get().brand(); }
    @Override public String tagline(Locale locale) {
        var snapshot = current.get();
        if (snapshot == null || snapshot.tagline().isBlank() || snapshot.tagline().equals(fallback.tagline(Locale.ENGLISH))) {
            return fallback.tagline(locale);
        }
        return snapshot.tagline();
    }
    @Override public List<Locale> languages() { return fallback.languages(); }
    @Override public Currency currency() { return fallback.currency(); }
    @Override public Observable changes() { return current.getReadOnlyProperty(); }
    public String themeCss() { return current.get() == null ? "" : current.get().themeCss(); }

    public void refresh() {
        if (closed || busy) return;
        busy = true;
        try {
            rest.get("settings/public").thenApply(this::parsed).whenComplete((settings, failure) -> Platform.runLater(() -> {
                busy = false;
                if (!closed && failure == null && !same(settings, current.get())) current.set(settings);
            }));
        } catch (RuntimeException failure) {
            busy = false;
        }
    }

    public void close() { closed = true; }

    private Snapshot parsed(JsonObject object) {
        String name = object.get("name").getAsString();
        String descriptor = object.get("descriptor").getAsString();
        if (name.isBlank() || descriptor.isBlank()) throw new IllegalArgumentException("Invalid restaurant identity");
        String reference = object.get("logoReference").getAsString();
        var theme = object.getAsJsonObject("theme");
        String style = "-rr-forest-green: " + color(theme, "primary") + "; -rr-cream: " + color(theme, "background")
                + "; -rr-gold: " + color(theme, "accent") + ";";
        var tagline = object.get("tagline");
        return new Snapshot(new RestaurantBrand(name, descriptor, logo(reference)), tagline == null || tagline.isJsonNull()
                ? "" : tagline.getAsString(), style);
    }

    private URL logo(String reference) {
        URI relative = URI.create(reference);
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawQuery() != null
                || relative.getRawFragment() != null || relative.getPath() == null
                || !relative.getPath().matches("/api/v1/settings/logo/(default|[a-f0-9-]{36})")) {
            throw new IllegalArgumentException("Invalid restaurant logo reference");
        }
        try {
            return address.base().resolve(relative).toURL();
        } catch (java.net.MalformedURLException invalid) {
            throw new IllegalArgumentException("Invalid restaurant logo reference", invalid);
        }
    }

    private static String color(JsonObject palette, String key) {
        String value = palette.get(key).getAsString();
        if (!value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid branding color");
        return value;
    }

    private static boolean same(Snapshot first, Snapshot second) {
        return second != null && first.brand().name().equals(second.brand().name())
                && first.brand().descriptor().equals(second.brand().descriptor())
                && first.brand().logo().toExternalForm().equals(second.brand().logo().toExternalForm())
                && first.tagline().equals(second.tagline()) && first.themeCss().equals(second.themeCss());
    }
}
