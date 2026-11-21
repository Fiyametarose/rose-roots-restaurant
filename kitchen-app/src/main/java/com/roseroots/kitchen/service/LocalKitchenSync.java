package com.roseroots.kitchen.service;

public final class LocalKitchenSync implements KitchenSync {
    @Override
    public Status status() {
        return Status.LOCAL;
    }

    @Override
    public Runnable onOrdersChanged(Runnable listener) {
        // The local provider has no remote notifications or reconnect operation.
        return () -> {};
    }
}
