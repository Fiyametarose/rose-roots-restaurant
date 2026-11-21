package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.AssistanceRequest;
import com.roseroots.kitchen.model.AssistanceRequest.Action;
import com.roseroots.kitchen.model.AssistanceRequest.Status;
import com.roseroots.kitchen.model.AssistanceRequest.Type;
import com.roseroots.kitchen.model.StaffMember;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class MockAssistanceService implements AssistanceService {
    private final Map<Long, AssistanceRequest> requests = new LinkedHashMap<>();
    private final Supplier<StaffMember> currentStaff;
    private final Clock clock;

    public MockAssistanceService(Clock clock, Supplier<StaffMember> currentStaff) {
        this.clock = clock;
        this.currentStaff = currentStaff;
    }

    // Models incoming customer requests. Replayed IDs preserve the existing request and its history.
    public synchronized boolean receive(long id, String reference, Type type, OffsetDateTime createdAt) {
        return requests.putIfAbsent(id, new AssistanceRequest(id, reference, type, createdAt, null, null)) == null;
    }

    @Override
    public synchronized List<AssistanceRequest> requests() {
        return List.copyOf(requests.values());
    }

    @Override
    public synchronized void acknowledge(long id) {
        AssistanceRequest request = requireStatus(id, Status.PENDING);
        requests.put(id, new AssistanceRequest(id, request.reference(), request.type(),
                request.createdAt(), recordAction(), null));
    }

    @Override
    public synchronized void resolve(long id) {
        AssistanceRequest request = requireStatus(id, Status.IN_PROGRESS);
        requests.put(id, new AssistanceRequest(id, request.reference(), request.type(),
                request.createdAt(), request.acknowledged(), recordAction()));
    }

    private AssistanceRequest requireStatus(long id, Status expected) {
        AssistanceRequest request = requests.get(id);
        if (request == null) {
            throw new IllegalArgumentException("Unknown assistance request.");
        }
        if (request.status() != expected) {
            throw new IllegalStateException("Request is already " + request.status().label.toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return request;
    }

    private Action recordAction() {
        StaffMember staff = currentStaff.get();
        if (staff == null) {
            throw new SecurityException("Select a staff profile before updating assistance.");
        }
        return new Action(staff.id(), OffsetDateTime.now(clock));
    }

    public static MockAssistanceService sample(Clock clock, Supplier<StaffMember> currentStaff) {
        MockAssistanceService service = new MockAssistanceService(clock, currentStaff);
        OffsetDateTime now = OffsetDateTime.now(clock);
        service.receive(301, "Table 07", Type.DRINKING_WATER, now.minusSeconds(30));
        service.receive(302, "Table 11", Type.WAITER, now.minusMinutes(1));
        service.receive(299, "Table 04", Type.NAPKINS, now.minusMinutes(3));
        service.receive(294, "Table 09", Type.CUTLERY, now.minusMinutes(6));
        service.receive(303, "Table 12", Type.BILL_HELP, now.minusMinutes(2));
        service.receive(304, "Table 03", Type.ORDER_HELP, now.minusMinutes(4));
        service.receive(305, "Pickup P-024", Type.OTHER, now.minusMinutes(5));
        String staffId = MockRushAuthorization.KITCHEN_STAFF.id();
        var acknowledged = service.requests.get(299L);
        service.requests.put(299L, new AssistanceRequest(299, acknowledged.reference(), acknowledged.type(),
                acknowledged.createdAt(), new Action(staffId, now.minusMinutes(2)), null));
        var resolved = service.requests.get(294L);
        service.requests.put(294L, new AssistanceRequest(294, resolved.reference(), resolved.type(),
                resolved.createdAt(), new Action(staffId, now.minusMinutes(5)), new Action(staffId, now.minusMinutes(4))));
        return service;
    }
}
