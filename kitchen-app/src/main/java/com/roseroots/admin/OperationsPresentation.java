package com.roseroots.admin;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Locale;
import static com.roseroots.admin.CatalogControls.text;

final class OperationsPresentation {
    static final String UNAVAILABLE = "Unavailable";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private OperationsPresentation() {}

    record Filters(String search, LocalDate from, LocalDate to, String orderStatus, String paymentStatus,
                   String serviceType, String paymentMethod, String sort) {
        Filters {
            search = search.strip();
            if (search.length() > 120 || from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("Use up to 120 search characters and a valid date range.");
            }
        }

        String query(String cursor) {
            var query = new ArrayList<String>();
            parameter(query, "q", search);
            parameter(query, "from", from);
            parameter(query, "to", to);
            parameter(query, "orderStatus", orderStatus);
            parameter(query, "paymentStatus", paymentStatus);
            parameter(query, "serviceType", serviceType);
            parameter(query, "paymentMethod", paymentMethod);
            parameter(query, "sort", sort);
            parameter(query, "cursor", cursor);
            parameter(query, "limit", 25);
            return String.join("&", query);
        }

        private static void parameter(ArrayList<String> query, String name, Object value) {
            if (value != null && !value.toString().isBlank()) {
                query.add(name + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
            }
        }
    }

    static String value(JsonObject object, String key) {
        String raw = text(object, key);
        return raw.isBlank() ? UNAVAILABLE : raw;
    }

    static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonObject object ? object : null;
    }

    static String amount(JsonObject object, String key, String currency) {
        String minor = text(object, key);
        if (minor.isBlank() || currency.isBlank()) {
            return UNAVAILABLE;
        }
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            if (digits < 0) {
                return UNAVAILABLE;
            }
            return currency + " " + new BigDecimal(minor).setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .movePointLeft(digits).setScale(digits).toPlainString();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return UNAVAILABLE;
        }
    }

    static String timestamp(JsonObject object, String key) {
        try {
            return TIME.format(Instant.parse(text(object, key)));
        } catch (java.time.DateTimeException error) {
            return UNAVAILABLE;
        }
    }

    static String state(String code) {
        if (code.isBlank()) {
            return UNAVAILABLE;
        }
        return switch (code) {
            case "DINE_IN" -> "Dine-in";
            case "MPESA", "M_PESA" -> "M-Pesa";
            default -> CatalogControls.words(code);
        };
    }

    static Failure classify(Exception failure) {
        if (failure instanceof AdminApi.Rejected rejected) {
            if (rejected.status == 401 || rejected.status == 403) {
                return Failure.SESSION;
            }
            if (rejected.status == 503 && rejected.code.equals("OPERATIONS_SOURCE_UNAVAILABLE")) {
                return Failure.UNAVAILABLE;
            }
            if (rejected.status == 404) {
                return Failure.NOT_FOUND;
            }
            if (rejected.status == 400) {
                return Failure.FILTERS;
            }
            return Failure.ERROR;
        }
        return failure instanceof IOException ? Failure.DISCONNECTED : Failure.ERROR;
    }

    enum Failure { SESSION, UNAVAILABLE, NOT_FOUND, FILTERS, ERROR, DISCONNECTED }
}
