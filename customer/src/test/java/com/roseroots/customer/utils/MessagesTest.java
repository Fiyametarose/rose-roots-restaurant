package com.roseroots.customer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MessagesTest {
    @Test
    void resolvesWelcomeTextForAnUnsupportedLocale() {
        assertEquals("Welcome", new Messages(Locale.forLanguageTag("es")).text("welcome.heading"));
    }

    @Test
    void resolvesArabicAndKeepsJubaArabicFallbackInEnglish() {
        var arabic = new Messages(Locale.forLanguageTag("ar"));
        assertEquals("ar", arabic.language());
        assertEquals("قائمة الطعام", arabic.text("menu.heading"));

        var jubaArabic = new Messages(Locale.forLanguageTag("pga"));
        assertEquals("en", jubaArabic.language());
        assertEquals("Menu", jubaArabic.text("menu.heading"));
    }
}
