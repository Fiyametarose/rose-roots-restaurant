package com.roseroots.kitchen.service.http;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

public record KitchenBackendConfig(Mode mode, URI origin) {
    public enum Mode { LOCAL, CONNECTED }

    public KitchenBackendConfig {
        java.util.Objects.requireNonNull(mode);
        origin = mode == Mode.CONNECTED ? httpsOrigin(origin) : null;
    }

    public static KitchenBackendConfig read(Map<String, String> environment) {
        String selected = environment.getOrDefault("ROSE_KITCHEN_MODE", "local").strip().toUpperCase(Locale.ROOT);
        return switch (selected) {
            case "LOCAL" -> new KitchenBackendConfig(Mode.LOCAL, null);
            case "CONNECTED" -> new KitchenBackendConfig(Mode.CONNECTED, parseOrigin(firstConfigured(environment)));
            default -> throw new IllegalArgumentException("Set ROSE_KITCHEN_MODE to local or connected.");
        };
    }

    private static String firstConfigured(Map<String, String> environment) {
        String shared = environment.get("ROSE_BACKEND_URL");
        return shared == null || shared.isBlank() ? environment.get("ROSE_ROOTS_BACKEND_URL") : shared;
    }

    private static URI parseOrigin(String value) {
        try {
            return value == null ? null : URI.create(value.strip());
        } catch (IllegalArgumentException ex) {
            throw invalidOrigin();
        }
    }

    private static URI httpsOrigin(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw invalidOrigin();
        }
        return URI.create("https://" + uri.getRawAuthority() + "/");
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException("Set ROSE_BACKEND_URL to the shared HTTPS server origin.");
    }
}
