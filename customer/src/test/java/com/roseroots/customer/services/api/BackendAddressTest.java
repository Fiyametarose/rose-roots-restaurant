package com.roseroots.customer.services.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

class BackendAddressTest {
    @Test void usesTheSameRemoteHostAndPortForRestAndWebSocket() {
        var address = BackendAddress.configured("https://192.0.2.20:8443").orElseThrow();
        assertEquals(URI.create("https://192.0.2.20:8443/api/v1/"), address.api());
        assertEquals(URI.create("wss://192.0.2.20:8443/ws/v1"), address.events());
        var named = BackendAddress.configured(" https://restaurant.example/ ").orElseThrow();
        assertEquals(URI.create("https://restaurant.example/api/v1/"), named.api());
        assertTrue(BackendAddress.configured(null).isEmpty());
        assertTrue(BackendAddress.configured(" ").isEmpty());
    }

    @Test void rejectsInsecureOrAmbiguousOrigins() {
        for (String origin : new String[]{"http://restaurant.example", "https://user@restaurant.example",
                "https://restaurant.example/api/v1/", "https://restaurant.example/?token=value",
                "https://restaurant.example/#secret", "restaurant.example", "https://restaurant.example:65536"}) {
            assertThrows(IllegalArgumentException.class, () -> BackendAddress.configured(origin), origin);
        }
    }
}
