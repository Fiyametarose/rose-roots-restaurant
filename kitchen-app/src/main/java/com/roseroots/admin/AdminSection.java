package com.roseroots.admin;

enum AdminSection {
    DASHBOARD("Dashboard", "Restaurant Overview"),
    MENU("Menu Management"),
    CATEGORIES("Categories & Drink Sections"),
    DISH("Add / Edit Dish"),
    PRICES("Prices & Availability"),
    ORDERS("Orders & Payments"),
    REVIEWS("Reviews & Insights"),
    BRANDING("Restaurant Branding"),
    TRANSLATIONS("Translations"),
    STAFF("Staff & Security"),
    KITCHEN("Kitchen Configuration"),
    TABLES("Tables & Dining Sessions");

    final String label;
    final String heading;

    AdminSection(String label) {
        this(label, label);
    }

    AdminSection(String label, String heading) {
        this.label = label;
        this.heading = heading;
    }
}
