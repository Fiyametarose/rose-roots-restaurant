package com.roseroots.customer.state;

import com.roseroots.customer.models.ConfiguredDish;
import com.roseroots.customer.models.MenuItem;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CartDraft {
    private final Map<UUID, Line> lines = new LinkedHashMap<>();

    void save(UUID id, ConfiguredDish dish) {
        UUID key = id == null ? UUID.randomUUID() : id;
        if (id != null && !lines.containsKey(id)) {
            throw new IllegalArgumentException("Cart item no longer exists");
        }
        var match = lines.values().stream().filter(line -> !line.id().equals(key)
                && identical(line.dish(), dish)
                && line.quantity() + dish.quantity() <= ConfiguredDish.MAX_QUANTITY).findFirst();
        if (match.isPresent()) {
            var existing = match.get();
            lines.remove(key);
            lines.put(existing.id(), new Line(existing.id(), dish.withQuantity(existing.quantity() + dish.quantity())));
        } else {
            lines.put(key, new Line(key, dish));
        }
    }

    private static boolean identical(ConfiguredDish first, ConfiguredDish second) {
        return first.item().equals(second.item()) && first.selections().equals(second.selections())
                && first.instructions().equals(second.instructions()) && first.allergy().equals(second.allergy());
    }

    public List<Line> lines() { return List.copyOf(lines.values()); }
    public Line line(UUID id) { return lines.get(id); }
    public int count() { return lines.values().stream().mapToInt(Line::quantity).sum(); }
    public BigDecimal total() {
        return lines.values().stream().map(line -> line.dish().total()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    void removeSubmitted(List<Line> submitted) {
        submitted.forEach(line -> {
            if (lines.get(line.id()) == line) lines.remove(line.id());
        });
    }

    void clear() { lines.clear(); }
    void remove(UUID id) { lines.remove(id); }

    public record Line(UUID id, ConfiguredDish dish) {
        public MenuItem item() { return dish.item(); }
        public int quantity() { return dish.quantity(); }
    }
}
