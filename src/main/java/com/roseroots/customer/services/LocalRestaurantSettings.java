package com.roseroots.customer.services;

import com.roseroots.customer.models.RestaurantBrand;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

public final class LocalRestaurantSettings implements RestaurantSettings {
    private final ResourceBundle settings = ResourceBundle.getBundle("restaurant", Locale.ROOT);

    @Override
    public List<com.roseroots.customer.models.CartPricing.Rule> cartCharges() {
        return java.util.stream.Stream.of("service", "tax")
                .filter(key -> settings.containsKey("cart.charge." + key + ".percent"))
                .map(key -> new com.roseroots.customer.models.CartPricing.Rule("cart.charge." + key,
                        new java.math.BigDecimal(settings.getString("cart.charge." + key + ".percent"))))
                .toList();
    }

    @Override
    public Currency currency() {
        return Currency.getInstance(settings.getString("currency"));
    }

    @Override
    public RestaurantBrand brand() {
        return new RestaurantBrand(settings.getString("name"), settings.getString("descriptor"),
                getClass().getResource(settings.getString("logo")));
    }

    @Override
    public String tagline(Locale locale) {
        String localizedKey = "tagline." + locale.getLanguage();
        return settings.getString(settings.containsKey(localizedKey) ? localizedKey : "tagline");
    }

    @Override
    public List<Locale> languages() {
        return Arrays.stream(settings.getString("languages").split(","))
                .map(String::strip)
                .map(Locale::forLanguageTag)
                .toList();
    }
}
