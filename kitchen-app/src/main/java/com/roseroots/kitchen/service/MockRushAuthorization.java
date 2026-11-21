package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.StaffMember;
import com.roseroots.kitchen.model.StaffMember.Role;
import java.util.List;

// This selector deliberately simulates identity locally. Production resolves identity on the shared server.
public final class MockRushAuthorization implements RushAuthorization {
    public static final StaffMember KITCHEN_STAFF = new StaffMember("kitchen-01", "Lina Abebe", Role.KITCHEN_STAFF);
    public static final StaffMember SUPERVISOR = new StaffMember("supervisor-01", "Maya Deng", Role.SUPERVISOR);
    public static final StaffMember MANAGER = new StaffMember("manager-01", "Samira Bekele", Role.MANAGER);
    public static final List<StaffMember> STAFF = List.of(KITCHEN_STAFF, SUPERVISOR, MANAGER);

    private StaffMember current = KITCHEN_STAFF;

    public synchronized void selectProfile(String staffId) {
        // Clear the previous identity even if a local profile is unavailable.
        current = STAFF.stream().filter(staff -> staff.id().equals(staffId)).findFirst().orElse(null);
    }

    public synchronized StaffMember currentStaff() {
        return current;
    }

    @Override
    public synchronized StaffMember requireAuthorizer() {
        if (current == null || !current.role().canAuthorizeRush()) {
            throw new SecurityException("A supervisor or manager must authorize Rush changes.");
        }
        return current;
    }
}
