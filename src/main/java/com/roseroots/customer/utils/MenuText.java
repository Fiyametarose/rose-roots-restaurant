package com.roseroots.customer.utils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

public final class MenuText {
    private MenuText() {
    }

    public static String label(Messages messages, Enum<?> entry) {
        return messages.text("catalog." + entry.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT)
                + "." + entry.name().toLowerCase(Locale.ROOT));
    }

    public static String price(BigDecimal amount, Currency currency, Locale locale) {
        var formatter = NumberFormat.getNumberInstance(locale);
        int digits = amount.stripTrailingZeros().scale() > 0 ? Math.max(0, currency.getDefaultFractionDigits()) : 0;
        formatter.setMinimumFractionDigits(digits);
        formatter.setMaximumFractionDigits(Math.max(digits, amount.stripTrailingZeros().scale()));
        return (currency.getCurrencyCode().equals("KES") ? "KSh" : currency.getCurrencyCode())
                + "\u00A0" + formatter.format(amount);
    }

    public static String allergyDisclosure(com.roseroots.customer.models.AllergyDeclaration declaration, Messages messages) {
        if (declaration.none()) return messages.text("custom.none");
        if (!declaration.disclosed()) return messages.text("custom.unreviewed");
        return java.util.stream.Stream.concat(declaration.allergens().stream().sorted()
                .map(value -> messages.text("catalog.allergen." + value.name().toLowerCase(Locale.ROOT))),
                java.util.stream.Stream.of(declaration.other()).filter(value -> !value.isBlank()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public static String languageName(Locale locale) {
        return switch (locale.getLanguage()) {
            case "en" -> "English";
            case "am" -> "አማርኛ";
            case "ar" -> "العربية";
            case "pga" -> "Juba Arabic";
            default -> locale.getDisplayLanguage(locale);
        };
    }
}
