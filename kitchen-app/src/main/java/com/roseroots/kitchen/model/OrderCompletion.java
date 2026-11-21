package com.roseroots.kitchen.model;

import java.time.OffsetDateTime;
import java.util.Objects;

public record OrderCompletion(Status status, OffsetDateTime at) {
    public enum Status { READY, SERVED, COLLECTED, CANCELLED }

    public OrderCompletion {
        Objects.requireNonNull(status);
    }

    public boolean active() {
        return status == Status.READY;
    }
}
