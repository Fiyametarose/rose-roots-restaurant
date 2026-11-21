package com.roseroots.kitchen.model;

import java.time.OffsetDateTime;

public record RushChange(long orderId, Action action, String reason, StaffMember actor, OffsetDateTime changedAt) {
    public enum Action { APPLY_RUSH, REMOVE_RUSH }

    public String staffId() {
        return actor.id();
    }
}
