package com.roseroots.kitchen.service.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads the externally managed secret again on reconnect or rotation. */
public final class WorkstationCredential implements StaffCredentialProvider {
    private final String location;
    private final String installed;

    public WorkstationCredential(String location) {
        this(location, null);
    }

    public WorkstationCredential(String location, String installed) {
        this.location = location;
        this.installed = installed;
    }

    @Override
    public Optional<String> bearerToken() {
        if (installed != null) return validated(installed);
        try {
            Path file = Path.of(location);
            if (!file.isAbsolute() || !Files.isRegularFile(file)) return Optional.empty();
            try (var input = Files.newInputStream(file)) {
                byte[] content = input.readNBytes(129);
                if (content.length > 128) return Optional.empty();
                String token = new String(content, StandardCharsets.US_ASCII).strip();
                return validated(token);
            }
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Optional<String> validated(String token) {
        return token.matches("ws_[A-Za-z0-9_-]{43}") ? Optional.of(token) : Optional.empty();
    }
}
