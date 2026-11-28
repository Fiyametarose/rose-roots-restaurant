package com.roseroots.customer.models;

import java.util.Locale;
import java.util.Map;

public record LocalizedText(String english, Map<String, String> translations) {
    public LocalizedText {
        translations = Map.copyOf(translations);
    }

    public LocalizedText(String english) {
        this(english, Map.of());
    }

    public String in(Locale locale) {
        return translations.getOrDefault(locale.toLanguageTag(),
                translations.getOrDefault(locale.getLanguage(), english));
    }
}
