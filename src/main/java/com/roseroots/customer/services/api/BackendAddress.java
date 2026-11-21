package com.roseroots.customer.services.api;

import java.net.URI;
import java.util.Optional;

public record BackendAddress(URI base) {
    public static final String ENVIRONMENT_VARIABLE = "ROSE_ROOTS_BACKEND_URL";

    public BackendAddress {
        if (base == null || !"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getRawPath().isEmpty() || base.getRawPath().equals("/"))
                || (base.getPort() != -1 && (base.getPort() < 1 || base.getPort() > 65535))) {
            throw new IllegalArgumentException("Set " + ENVIRONMENT_VARIABLE + " to an HTTPS origin with an optional port.");
        }
        base = URI.create("https://" + base.getRawAuthority() + "/");
    }

    public static Optional<BackendAddress> configured(String value) {
        return value == null || value.isBlank() ? Optional.empty()
                : Optional.of(new BackendAddress(URI.create(value.strip())));
    }

    public URI api() { return base.resolve("/api/v1/"); }

    public URI events() { return URI.create("wss://" + base.getRawAuthority() + "/ws/v1"); }
}
