package com.roseroots.kitchen.service.http;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

public record ConnectedAssistanceRequest(UUID id, UUID sessionId, String destination, Type type, String note,
                                         State status, long version, Instant updatedAt, UUID ownerStaffId) {
    public enum Type {
        WAITER("Call waiter"), DRINKING_WATER("Drinking water"), CUTLERY("Cutlery"), NAPKINS("Napkins"),
        ORDER_HELP("Order help"), BILL("Bill help"), OTHER("Other assistance");
        public final String label;
        Type(String label) { this.label = label; }
    }

    public enum State {
        SUBMITTED("Submitted", "Acknowledge"), ACKNOWLEDGED("Acknowledged", "Start"),
        IN_PROGRESS("In progress", "Complete"), COMPLETED("Completed", "");
        public final String label;
        public final String action;
        State(String label, String action) { this.label = label; this.action = action; }
        State next() { return values()[ordinal() + 1]; }
    }

    static ConnectedAssistanceRequest parse(JsonObject object) {
        UUID id = canonical(object.get("id").getAsString());
        UUID session = canonical(object.get("sessionId").getAsString());
        State status = State.valueOf(object.get("status").getAsString());
        long version = object.getAsJsonPrimitive("version").getAsBigDecimal().longValueExact();
        if (version < status.ordinal() + 1L) throw new IllegalArgumentException("Invalid assistance version");
        return new ConnectedAssistanceRequest(id, session, object.get("serviceDestination").getAsString(),
                Type.valueOf(object.get("type").getAsString()), object.get("note").getAsString(), status, version,
                Instant.parse(object.get("updatedAt").getAsString()),
                object.has("ownerStaffId") && !object.get("ownerStaffId").isJsonNull() ? canonical(object.get("ownerStaffId").getAsString()) : null);
    }

    private static UUID canonical(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Invalid assistance UUID");
        return id;
    }
}
