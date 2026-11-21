package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.DiningSessionService;
import com.roseroots.customer.services.OrderService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class ConnectedTerminal implements DiningSessionService {
    private final SpringBootOrderService rest;
    private final Clock clock;

    public ConnectedTerminal(SpringBootOrderService rest, Clock clock) {
        this.rest = rest;
        this.clock = clock;
    }

    @Override public CompletionStage<TerminalSession> openOrResume(ServiceMode mode) {
        return rest.get("device").thenApply(ConnectedTerminal::readDevice).thenCompose(device ->
                rest.startSession(mode).thenApply(response -> readSession(response, device, mode)));
    }

    private static TerminalDevice readDevice(JsonObject response) {
        var table = response.get("table");
        return new TerminalDevice(id(response, "id"), table.isJsonNull() ? null : readTable(table.getAsJsonObject()), false);
    }

    private TerminalSession readSession(JsonObject response, TerminalDevice device, ServiceMode mode) {
        if (!mode.name().equals(response.get("mode").getAsString()) || !"OPEN".equals(response.get("status").getAsString())
                || !Instant.parse(response.get("expiresAt").getAsString()).isAfter(clock.instant())
                || !response.get("closedAt").isJsonNull()) throw new OrderService.Problem("INVALID_RESPONSE", false);
        String pickup = null;
        if (mode == ServiceMode.DINE_IN) {
            if (!Objects.equals(device.table(), readTable(response.getAsJsonObject("table")))) {
                throw new OrderService.Problem("INVALID_RESPONSE", false);
            }
        } else {
            pickup = response.get("pickupReference").getAsString();
        }
        return new TerminalSession(id(response, "id"), device, mode, TerminalSession.Status.OPEN, pickup, List.of());
    }

    private static TerminalDevice.TableAssignment readTable(JsonObject table) {
        var display = table.get("displayNumber").getAsString();
        if (display.isBlank()) throw new OrderService.Problem("INVALID_RESPONSE", false);
        return new TerminalDevice.TableAssignment(id(table, "id"), display);
    }

    private static String id(JsonObject value, String key) {
        return Ordering.canonicalId(value.get(key).getAsString()).toString();
    }
}
