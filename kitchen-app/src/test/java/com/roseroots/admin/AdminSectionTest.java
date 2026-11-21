package com.roseroots.admin;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminSectionTest {
    @Test
    void sidebarUsesAllTwelveReferenceDestinationsInOrder() {
        assertEquals(List.of("Dashboard", "Menu Management", "Categories & Drink Sections", "Add / Edit Dish",
                        "Prices & Availability", "Orders & Payments", "Reviews & Insights", "Restaurant Branding",
                        "Translations", "Staff & Security", "Kitchen Configuration", "Tables & Dining Sessions"),
                Arrays.stream(AdminSection.values()).map(section -> section.label).toList());
    }

    @Test
    void adminClientRequiresHttpsForRemoteCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new AdminApi("http://192.0.2.1:8080"));
        assertThrows(IllegalArgumentException.class, () -> new AdminApi("https://user@example.com"));
    }

    @Test
    void adminUsesTheSharedOriginUnlessAnExplicitOverrideIsSet() {
        String shared = "https://192.0.2.1:8443";
        assertEquals(shared, AdminApi.configuredAddress(" ", " " + shared + " "));
        assertEquals("https://admin.example.com", AdminApi.configuredAddress("https://admin.example.com", shared));
        assertEquals("http://127.0.0.1:8080", AdminApi.configuredAddress(null, null));
    }
}
