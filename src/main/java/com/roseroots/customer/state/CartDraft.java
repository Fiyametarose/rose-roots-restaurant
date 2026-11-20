package com.roseroots.customer.state;

import com.roseroots.customer.models.MenuItem;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CartDraft {
    private final Map<String, Line> lines = new LinkedHashMap<>();

    void add(MenuItem item) {
        lines.compute(item.id(), (id, previous) -> previous == null
                ? new Line(item, 1) : new Line(previous.item(), previous.quantity() + 1));
    }

    public List<Line> lines() { return List.copyOf(lines.values()); }

    public int count() { return lines.values().stream().mapToInt(Line::quantity).sum(); }

    public BigDecimal total() {
        return lines.values().stream().map(line -> line.item().price().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    void clear() { lines.clear(); }

    public record Line(MenuItem item, int quantity) {
    }
}
