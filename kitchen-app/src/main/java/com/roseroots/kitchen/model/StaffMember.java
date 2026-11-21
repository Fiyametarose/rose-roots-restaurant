package com.roseroots.kitchen.model;

public record StaffMember(String id, String name, Role role) {
    public enum Role {
        KITCHEN_STAFF("Kitchen Staff"), SUPERVISOR("Supervisor"), MANAGER("Manager");

        public final String label;

        public boolean canAuthorizeRush() {
            return this == SUPERVISOR || this == MANAGER;
        }

        Role(String label) {
            this.label = label;
        }
    }
}
