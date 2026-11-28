package com.roseroots.customer.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;

public record CartPricing(BigDecimal subtotal, List<Charge> charges, BigDecimal total) {
    public CartPricing {
        charges = List.copyOf(charges);
    }

    public record Rule(String labelKey, BigDecimal percent) {
        public Rule {
            if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("Charge percentage must be between zero and 100");
            }
        }
    }

    public record Charge(Rule rule, BigDecimal amount) {
    }

    public static CartPricing calculate(BigDecimal subtotal, List<Rule> rules, Currency currency) {
        int scale = Math.max(0, currency.getDefaultFractionDigits());
        var charges = rules.stream().map(rule -> new Charge(rule, subtotal.multiply(rule.percent())
                .movePointLeft(2).setScale(scale, RoundingMode.HALF_UP))).toList();
        var total = charges.stream().map(Charge::amount).reduce(subtotal, BigDecimal::add);
        return new CartPricing(subtotal, charges, total);
    }
}
