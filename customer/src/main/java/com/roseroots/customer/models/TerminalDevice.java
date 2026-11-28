package com.roseroots.customer.models;

public record TerminalDevice(String deviceId, TableAssignment table, boolean demo) {
    public record TableAssignment(String tableId, String displayNumber) {
    }
}
