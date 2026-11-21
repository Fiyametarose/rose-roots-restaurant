package com.roseroots.customer.services;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LocalDraftSessionService implements DiningSessionService {
    private final TerminalDevice device;
    private final EnumMap<ServiceMode, TerminalSession> contexts = new EnumMap<>(ServiceMode.class);

    public LocalDraftSessionService(TerminalDevice device) {
        this.device = device;
    }

    @Override
    public synchronized CompletionStage<TerminalSession> openOrResume(ServiceMode mode) {
        return CompletableFuture.completedFuture(contexts.computeIfAbsent(mode, key -> new TerminalSession(
                null, key == ServiceMode.DINE_IN ? device : new TerminalDevice(device.deviceId(), null, false),
                key, TerminalSession.Status.LOCAL_DRAFT, null, List.of(), UUID.randomUUID().toString())));
    }
}
