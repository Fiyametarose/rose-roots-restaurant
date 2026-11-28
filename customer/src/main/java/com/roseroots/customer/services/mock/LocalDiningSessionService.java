package com.roseroots.customer.services.mock;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.models.TerminalSession.Status;
import com.roseroots.customer.services.DiningSessionService;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LocalDiningSessionService implements DiningSessionService {
    private final TerminalDevice device;
    private final EnumMap<ServiceMode, TerminalSession> active = new EnumMap<>(ServiceMode.class);
    private final LocalTakeawaySessionService takeaway;

    public LocalDiningSessionService(boolean demo) {
        if (demo) {
            var config = ResourceBundle.getBundle("demo-terminal", Locale.ROOT);
            var table = new TerminalDevice.TableAssignment(config.getString("table.id"),
                    config.getString("table.displayNumber"));
            device = new TerminalDevice(config.getString("device.id"), table, true);
        } else {
            device = new TerminalDevice("local-unprovisioned", null, false);
        }
        takeaway = demo ? new LocalTakeawaySessionService(device.deviceId()) : null;
    }

    @Override
    public synchronized CompletionStage<TerminalSession> openOrResume(ServiceMode mode) {
        if (mode == ServiceMode.TAKEAWAY && takeaway != null) {
            return takeaway.openOrResume();
        }
        if (!device.demo()) {
            var status = mode == ServiceMode.DINE_IN ? Status.UNASSIGNED : Status.UNAVAILABLE;
            return CompletableFuture.completedFuture(new TerminalSession(null, device, mode, status, null, List.of()));
        }

        return CompletableFuture.completedFuture(active.computeIfAbsent(mode, key -> {
            return new TerminalSession(UUID.randomUUID().toString(), device, key, Status.OPEN, null, List.of());
        }));
    }

    /** Demo equivalent of a staff-authorized session close. There is no customer close action. */
    public synchronized TerminalSession closeForStaff(ServiceMode mode) {
        if (mode == ServiceMode.TAKEAWAY && takeaway != null) {
            return takeaway.closeForStaff();
        }
        var session = active.remove(mode);
        return session == null ? null : new TerminalSession(session.sessionId(), session.device(), mode,
                Status.CLOSED, session.pickupReference(), session.orders());
    }
}
