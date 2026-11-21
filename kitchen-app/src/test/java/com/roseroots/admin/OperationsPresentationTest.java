package com.roseroots.admin;

import com.google.gson.JsonParser;
import java.net.ConnectException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static com.roseroots.admin.OperationsPresentation.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsPresentationTest {
    @Test
    void orderRowUsesIndependentStatusesAndExactMinorUnitMoney() {
        var row = JsonParser.parseString("""
                {"orderStatus":"READY","paymentStatus":"UNPAID","totalMinor":185005,"itemCount":1}
                """).getAsJsonObject();
        assertEquals("Ready", state(value(row, "orderStatus")));
        assertEquals("Unpaid", state(value(row, "paymentStatus")));
        assertEquals("KES 1850.05", amount(row, "totalMinor", "KES"));
        assertEquals("1", value(row, "itemCount"));
        assertEquals(UNAVAILABLE, amount(row, "confirmedSalesMinor", "KES"));
    }

    @Test
    void searchAndFiltersAreEncodedAndDatesValidated() {
        var day = LocalDate.of(2026, 11, 20);
        var filters = new Filters(" Table & pickup ", day, day, "READY", "UNPAID", "DINE_IN", "CASH", "TOTAL_DESC");
        assertEquals("q=Table+%26+pickup&from=2026-11-20&to=2026-11-20&orderStatus=READY"
                + "&paymentStatus=UNPAID&serviceType=DINE_IN&paymentMethod=CASH&sort=TOTAL_DESC&cursor=a%2Bb&limit=25",
                filters.query("a+b"));
        assertThrows(IllegalArgumentException.class, () ->
                new Filters("", day.plusDays(1), day, "", "", "", "", "NEWEST"));
    }

    @Test
    void unavailableServiceNetworkFailureAndSessionEndAreDistinct() {
        assertEquals(Failure.SESSION, classify(new AdminApi.Rejected(401, "")));
        assertEquals(Failure.SESSION, classify(new AdminApi.Rejected(403, "")));
        assertEquals(Failure.UNAVAILABLE, classify(new AdminApi.Rejected(503,
                "{\"code\":\"OPERATIONS_SOURCE_UNAVAILABLE\"}")));
        assertEquals(Failure.ERROR, classify(new AdminApi.Rejected(503, "")));
        assertEquals(Failure.DISCONNECTED, classify(new ConnectException()));
    }

    @Test
    void acceptedSnapshotValuesAreNotRecomputedAndEmptyHasMeasuredZeroOnlyWhenSupplied() {
        var detail = JsonParser.parseString("""
                {"items":[],"totals":{"subtotalMinor":165000,"serviceMinor":10000,"taxMinor":10000,"totalMinor":185000},
                 "summary":{"totalOrders":0}}
                """).getAsJsonObject();
        assertTrue(CatalogControls.rows(detail, "items").isEmpty());
        assertEquals("KES 1850.00", amount(object(detail, "totals"), "totalMinor", "KES"));
        assertEquals("0", value(object(detail, "summary"), "totalOrders"));
        assertEquals(UNAVAILABLE, value(object(detail, "summary"), "failedPayments"));
    }
}
