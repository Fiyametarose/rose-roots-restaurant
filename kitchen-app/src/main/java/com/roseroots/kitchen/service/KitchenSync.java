package com.roseroots.kitchen.service;

public interface KitchenSync {
    enum Status {
        LOCAL("Local mode — Server not connected"),
        LOADING("Loading server tickets"),
        OFFLINE("Server not connected"),
        FAILED("Server request failed"),
        CONNECTED("Server connected");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    Status status();

    default void refresh() {}

    default boolean busy() { return false; }

    default String message() { return ""; }

    // Future WebSocket invalidations: callback on the FX thread, re-read authoritative service snapshots.
    Runnable onOrdersChanged(Runnable listener);
}
