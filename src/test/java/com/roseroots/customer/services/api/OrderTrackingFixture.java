package com.roseroots.customer.services.api;

import com.roseroots.customer.models.*;
import com.roseroots.customer.services.mock.MockMenuService;
import com.roseroots.customer.state.CustomerState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OrderTrackingFixture {
    public static final UUID SESSION = id(77);
    public static final UUID WAT = id(11);
    public static final UUID FISH = id(12);
    public static final UUID KISRA = id(13);
    public static final UUID ACTIVE_ORDER = id(102);
    private static final AllergyDeclaration NONE = new AllergyDeclaration(true, Set.of(), "");

    private OrderTrackingFixture() {}

    private static UUID id(int number) { return UUID.fromString("%08d-0000-4000-8000-000000000001".formatted(number)); }

    public static CustomerState customer() {
        var state = new CustomerState();
        state.activateSession(new TerminalSession(SESSION.toString(), new TerminalDevice("test-only",
                new TerminalDevice.TableAssignment("fixture-table", "07"), false),
                ServiceMode.DINE_IN, TerminalSession.Status.OPEN, null, List.of()), false);
        var sample = new MockMenuService(true).loadCatalog().toCompletableFuture().join();
        state.menu().setCatalog(new MenuCatalog(sample.categories(), List.of(
                copy(sample.item("doro-wat"), WAT),
                copy(sample.item("grilled-fish"), FISH),
                copy(sample.items().stream().filter(i -> i.id().startsWith("kisra")).findFirst().orElseThrow(), KISRA)), true));
        return state;
    }

    public static OrderHistory history(Instant now) {
        var doro = line(21, WAT, "Doro Wat", 145000, Ordering.Status.RECEIVED,
                Ordering.Review.PENDING_ALLERGY_REVIEW, true, "");
        var declined = line(22, FISH, "Fish tasting portion", 55000, Ordering.Status.CANCELLED,
                Ordering.Review.DECLINED, false, "The kitchen could not accommodate this request.");
        var fish = line(23, FISH, "Grilled Fish", 175000, Ordering.Status.READY,
                Ordering.Review.APPROVED_FOR_PREPARATION, true, "");
        var kisra = line(24, KISRA, "Kisra with Bamia", 125000, Ordering.Status.SERVED, Ordering.Review.NOT_REQUIRED, true, "");
        var first = order(102, now.minusSeconds(180), Ordering.Status.RECEIVED, List.of(doro, declined), totals(145000));
        var second = order(101, now.minusSeconds(1200), Ordering.Status.READY, List.of(fish), totals(175000));
        var third = order(100, now.minusSeconds(2800), Ordering.Status.SERVED, List.of(kisra), totals(125000));
        var estimate = new WaitEstimate(Duration.ofMinutes(8), Duration.ofMinutes(12), now, now.plusSeconds(90));
        return new OrderHistory(SESSION, List.of(third, first, second), Map.of(first.id(), first.totals(),
                second.id(), second.totals(), third.id(), third.totals()), totals(445000), Map.of(first.id(), estimate));
    }

    private static Ordering.Accepted order(int id, Instant accepted, Ordering.Status status,
                                           List<Ordering.AcceptedLine> lines, Ordering.Totals total) {
        return new Ordering.Accepted(id(id), SESSION, ServiceMode.DINE_IN, status, accepted, NONE, lines, total);
    }

    private static Ordering.AcceptedLine line(int number, UUID dish, String name, long price,
                                              Ordering.Status status, Ordering.Review review, boolean billed, String note) {
        var allergy = review == Ordering.Review.PENDING_ALLERGY_REVIEW
                ? new AllergyDeclaration(false, Set.of(AllergyDeclaration.Allergen.MILK), "Sesame") : NONE;
        return new Ordering.AcceptedLine(id(number), new Ordering.Price(id(number + 1000), dish, name,
                1, price, List.of(), price), allergy, "", status, review, billed, note);
    }

    public static Ordering.Totals totals(long amount) { return new Ordering.Totals(amount, 0, 0, amount, "KES"); }

    private static MenuItem copy(MenuItem seed, UUID id) {
        return new MenuItem(id.toString(), seed.categoryId(), seed.cuisine(), seed.name(), seed.summary(), seed.description(),
                seed.price(), seed.image(), seed.ingredients(), seed.safety(), seed.spice(), seed.availability(),
                seed.highlights(), seed.preparationTime(), seed.customizationGroups());
    }
}
