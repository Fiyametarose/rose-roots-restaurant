package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.WaitEstimate;
import com.roseroots.customer.utils.MenuText;
import com.roseroots.customer.utils.Messages;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import javafx.geometry.NodeOrientation;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

class CustomerLocalizationTest {
    @Test void shippedAmharicAndArabicBundlesCoverEveryEnglishKey() throws Exception {
        var english = bundle("");
        for (var language : new String[]{"am", "ar"}) {
            var localized = bundle("_" + language);
            for (var key : english.stringPropertyNames()) {
                assertNotNull(localized.getProperty(key), () -> language + " missing " + key);
                assertFalse(localized.getProperty(key).isBlank(), () -> language + " empty " + key);
            }
            var messages = new Messages(Locale.forLanguageTag(language));
            for (var key : new String[]{"confirm.allergyBlocked", "assistance.connection.error",
                    "orders.status.collected", "review.kitchenPrivacy"}) {
                assertNotEquals(english.getProperty(key), messages.text(key));
            }
        }
    }

    @Test void ArabicDirectionDoesNotLeakWhenSwitchingLanguage() {
        var pane = new StackPane();
        new Messages(Locale.forLanguageTag("ar")).applyDirection(pane);
        assertEquals(NodeOrientation.RIGHT_TO_LEFT, pane.getNodeOrientation());
        new Messages(Locale.forLanguageTag("am")).applyDirection(pane);
        assertEquals(NodeOrientation.LEFT_TO_RIGHT, pane.getNodeOrientation());
    }

    @Test void rendersValidServerEstimateRangesWithLocalizedNumbersAndUnits() {
        var now = Instant.parse("2026-11-21T17:00:00Z");
        var range = new WaitEstimate(Duration.ofMinutes(8), Duration.ofMinutes(12), now, now.plusSeconds(60));
        assertEquals("8–12 min", MenuText.waitRange(range, Locale.ENGLISH, new Messages(Locale.ENGLISH)));
        var arabic = Locale.forLanguageTag("ar");
        assertTrue(MenuText.waitRange(range, arabic, new Messages(arabic)).endsWith("دقيقة"));
        assertFalse(MenuText.waitRange(range, arabic, new Messages(arabic)).contains("8–12"));
        assertTrue(WaitEstimateService.current(java.util.Optional.of(range), now.plusSeconds(60)).isEmpty());
    }

    private static Properties bundle(String suffix) throws Exception {
        var path = "/i18n/messages" + suffix + ".properties";
        var data = new Properties();
        try (var stream = CustomerLocalizationTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            data.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return data;
    }
}
