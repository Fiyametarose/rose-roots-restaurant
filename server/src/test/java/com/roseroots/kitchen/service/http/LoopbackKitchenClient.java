package com.roseroots.kitchen.service.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;

/** Connects the real adapter to the ephemeral Spring Boot server used by PostgreSQL tests. */
public final class LoopbackKitchenClient {
    private LoopbackKitchenClient() {}

    public static SpringBootKitchenService create(int port, String staffToken, SSLContext trust) {
        return new SpringBootKitchenService(URI.create("https://127.0.0.1:" + port), () -> Optional.of(staffToken),
                HttpClient.newBuilder().sslContext(trust).build(), Duration.ofSeconds(5), Runnable::run,
                Duration.ofMillis(120), Duration.ofSeconds(1));
    }
}
