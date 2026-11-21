package com.roseroots.kitchen.service;

public interface KitchenSync {
    enum Status {
        LOCAL("Local mode — Server not connected"),
        OFFLINE("Server not connected"),
        CONNECTED("Server connected");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    Status status();

    // Future WebSocket invalidations: callback on the FX thread, re-read authoritative service snapshots.
    Runnable onOrdersChanged(Runnable listener);
}
