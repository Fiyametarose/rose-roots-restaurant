package com.roseroots.kitchen.service.http;

import java.util.Optional;

@FunctionalInterface
public interface StaffCredentialProvider {
    // Supplies an already issued credential from the current staff session. Must not perform blocking I/O.
    Optional<String> bearerToken();
}
