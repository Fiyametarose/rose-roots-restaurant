package com.roseroots.customer.services.mock;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.TakeawaySessionService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LocalTakeawaySessionService implements TakeawaySessionService {
    private final TerminalDevice device;
    private TerminalSession active;
    private int nextReference = 1;

    public LocalTakeawaySessionService(String demoDeviceId) {
        device = new TerminalDevice(demoDeviceId, null, true);
    }

    @Override
    public synchronized CompletionStage<TerminalSession> openOrResume() {
        if (active == null) {
            String reference = "DEMO-" + String.format(Locale.ROOT, "%03d", nextReference++);
            active = new TerminalSession(UUID.randomUUID().toString(), device, ServiceMode.TAKEAWAY,
                    TerminalSession.Status.OPEN, reference, List.of());
        }
        return CompletableFuture.completedFuture(active);
    }

    public synchronized TerminalSession closeForStaff() {
        if (active == null) {
            return null;
        }
        var closed = new TerminalSession(active.sessionId(), device, active.mode(), TerminalSession.Status.CLOSED,
                active.pickupReference(), active.orders());
        active = null;
        return closed;
    }
}
