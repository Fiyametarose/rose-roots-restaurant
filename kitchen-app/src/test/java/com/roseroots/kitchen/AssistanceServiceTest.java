package com.roseroots.kitchen;

import com.roseroots.kitchen.model.AssistanceRequest.Action;
import com.roseroots.kitchen.model.AssistanceRequest.Status;
import com.roseroots.kitchen.model.AssistanceRequest.Type;
import com.roseroots.kitchen.service.MockAssistanceService;
import com.roseroots.kitchen.service.MockRushAuthorization;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistanceServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-20T12:00:00-07:00");
    private final Clock clock = Clock.fixed(NOW.toInstant(), NOW.getOffset());
    private final MockRushAuthorization staff = new MockRushAuthorization();
    private final MockAssistanceService service = new MockAssistanceService(clock, staff::currentStaff);

    @Test
    void recordsBothActorsAndTimestampsWithoutChangingRequestDetails() {
        OffsetDateTime received = NOW.minusMinutes(1);
        assertTrue(service.receive(301, "Table 07", Type.DRINKING_WATER, received));
        var pending = service.requests().getFirst();
        assertEquals(Status.PENDING, pending.status());
        assertEquals(received, pending.createdAt());

        service.acknowledge(301);
        var acknowledged = service.requests().getFirst();
        assertEquals(Status.IN_PROGRESS, acknowledged.status());
        assertEquals(new Action(MockRushAuthorization.KITCHEN_STAFF.id(), NOW), acknowledged.acknowledged());
        assertNull(acknowledged.resolved());

        staff.selectProfile(MockRushAuthorization.MANAGER.id());
        service.resolve(301);
        var resolved = service.requests().getFirst();
        assertEquals(Status.RESOLVED, resolved.status());
        assertEquals(acknowledged.acknowledged(), resolved.acknowledged());
        assertEquals(new Action(MockRushAuthorization.MANAGER.id(), NOW), resolved.resolved());
        assertEquals(301, resolved.id());
        assertEquals("Table 07", resolved.reference());
        assertEquals(Type.DRINKING_WATER, resolved.type());
        assertEquals(received, resolved.createdAt());
    }

    @Test
    void rejectsSkippedAndRepeatedActionsAndDeduplicatesIncomingIds() {
        service.receive(301, "Session 44", Type.BILL_HELP, NOW);
        assertThrows(IllegalArgumentException.class, () -> service.resolve(999));
        assertThrows(IllegalStateException.class, () -> service.resolve(301));
        assertEquals(Status.PENDING, service.requests().getFirst().status());
        staff.selectProfile("unknown");
        assertThrows(SecurityException.class, () -> service.acknowledge(301));
        assertEquals(Status.PENDING, service.requests().getFirst().status());

        staff.selectProfile(MockRushAuthorization.KITCHEN_STAFF.id());
        service.acknowledge(301);
        assertThrows(IllegalStateException.class, () -> service.acknowledge(301));
        service.resolve(301);
        var resolved = service.requests().getFirst();

        assertThrows(IllegalStateException.class, () -> service.resolve(301));
        assertThrows(IllegalStateException.class, () -> service.acknowledge(301));
        assertFalse(service.receive(301, "Table 12", Type.OTHER, NOW));
        assertEquals(List.of(resolved), service.requests());
    }

    @Test
    void everyRequestTypeCanCompleteTheSameLifecycle() {
        long id = 1;
        for (Type type : Type.values()) {
            service.receive(id, "Table 01", type, NOW);
            service.acknowledge(id);
            service.resolve(id);
            id++;
        }
        assertEquals(List.of(Type.values()), service.requests().stream().map(request -> request.type()).toList());
        assertTrue(service.requests().stream().allMatch(request -> request.status() == Status.RESOLVED));
    }
}
