package com.roseroots.customer.models;

import java.math.BigDecimal;

/** A server-accepted order and its confirmed contribution to the session bill. */
public record SessionOrder(String orderId, String sessionId, BigDecimal confirmedAmount) {
}
