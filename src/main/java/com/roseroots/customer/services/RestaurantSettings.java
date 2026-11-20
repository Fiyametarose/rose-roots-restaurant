package com.roseroots.customer.services;

import com.roseroots.customer.models.RestaurantBrand;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

public interface RestaurantSettings {
    RestaurantBrand brand();

    String tagline(Locale locale);

    List<Locale> languages();

    Currency currency();
}
