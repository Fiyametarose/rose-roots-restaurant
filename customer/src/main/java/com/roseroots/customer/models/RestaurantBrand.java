package com.roseroots.customer.models;

import java.net.URL;

public record RestaurantBrand(String name, String descriptor, URL logo) {
    public String windowTitle() {
        return name + " " + descriptor;
    }
}
