package com.roseroots.customer.utils;

import java.util.Locale;
import java.util.ResourceBundle;

public final class Messages {
    private final ResourceBundle bundle;

    public Messages(Locale locale) {
        var selected = ResourceBundle.getBundle("i18n.messages", locale);
        bundle = selected.getLocale().getLanguage().equals(locale.getLanguage())
                ? selected : ResourceBundle.getBundle("i18n.messages", Locale.ROOT);
    }

    public String text(String key) {
        return bundle.getString(key);
    }

    public String language() {
        return bundle.getLocale().equals(Locale.ROOT) ? "en" : bundle.getLocale().getLanguage();
    }
}
