package com.roseroots.server.admin;

import java.util.List;

/** A null field indicates an unavailable source. An empty list is a successful empty result. */
public record DashboardSnapshot(Long ordersToday, Long activeDiningSessions, String salesToday,
                                String averageRating, List<Order> recentOrders,
                                List<String> soldOutItems, List<String> serviceAlerts) {
    public record Order(String time, String number, String tableOrType, String items,
                        String amount, String status) {}
}
