package com.roseroots.customer.state;

import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.services.OrderService;
import static com.roseroots.customer.models.Ordering.canonicalId;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;


/** Scoped to the application state. Call from the UI thread and dispatch completions to that same thread. */
public final class OrderConfirmation {
    public enum Phase { IDLE, QUOTING, QUOTED, SENDING, RETRY, ERROR, ACCEPTED }

    private final CustomerState customer;
    private final OrderService service;
    private final Clock clock;
    private final Executor ui;
    private Snapshot quotedDraft;
    private Ordering.Quote quote;
    private final java.util.Map<String, Attempt> unresolvedAttempts = new java.util.HashMap<>();
    private Phase phase = Phase.IDLE;
    private String problem = "";
    private String problemContext;
    private List<String> diagnostics = List.of();
    private final java.util.Map<String, Ordering.Draft> conflicts = new java.util.HashMap<>();

    public List<String> diagnostics() { return problem().isEmpty() ? List.of() : diagnostics; }

    public boolean conflicted() {
        var conflict = conflicts.get(context());
        if (conflict == null) return false;
        try { return conflict.equals(snapshot().draft()); }
        catch (RuntimeException invalid) { return false; }
    }
    private Ordering.Accepted accepted;

    public OrderConfirmation(CustomerState customer, OrderService service, Clock clock, Executor ui) {
        this.customer = customer;
        this.service = service;
        this.clock = clock;
        this.ui = ui;
    }

    public Phase phase() { return phase; }
    public String problem() {
        return Objects.equals(problemContext, context()) ? problem : "";
    }
    public boolean busy() { return phase == Phase.QUOTING || phase == Phase.SENDING; }
    public boolean active() {
        var session = customer.terminalSession();
        return service.available() && session != null && session.canSubmitOrders();
    }

    public Ordering.Accepted accepted() {
        return accepted != null && sameSession(accepted.sessionId(), accepted.mode()) ? accepted : null;
    }

    public Ordering.Quote quote() {
        return quote != null && quote.expiresAt().isAfter(clock.instant()) && matches(quotedDraft) ? quote : null;
    }

    public boolean retryPossible() { return active() && unresolved(); }

    public boolean unresolved() { return unresolvedAttempts.containsKey(context()); }

    private String context() {
        var session = customer.terminalSession();
        return session == null ? null : session.cartContextId();
    }

    public boolean requiresAllergyAcknowledgement() {
        return customer.sessionAllergy().disclosed()
                || customer.cart().lines().stream().anyMatch(line -> line.dish().allergy().disclosed());
    }

    public boolean hasIngredientConflict() {
        return customer.cart().lines().stream().anyMatch(line -> !customer.ingredientConflicts(line.dish()).isEmpty());
    }

    public boolean canQuote() {
        return active() && !busy() && !unresolved() && !conflicted()
                && !customer.cart().lines().isEmpty() && !hasIngredientConflict();
    }

    public void requestQuote(Runnable changed) {
        if (!canQuote()) return;
        final Snapshot snapshot;
        try {
            snapshot = snapshot();
        } catch (InvalidReference invalid) {
            fail("INVALID_SELECTION");
            diagnostics = List.of(invalid.getMessage());
            changed.run();
            return;
        } catch (RuntimeException invalid) {
            fail("INVALID_SELECTION");
            changed.run();
            return;
        }
        quote = null;
        accepted = null;
        problem = "";
        diagnostics = List.of();
        phase = Phase.QUOTING;
        changed.run();
        complete(() -> service.quote(snapshot.draft()), (value, error) -> {
            if (error != null) fail(error, snapshot);
            else if (!matches(snapshot) || value == null || !value.sessionId().equals(snapshot.draft().sessionId())
                    || !value.expiresAt().isAfter(clock.instant())
                    || !validPricing(snapshot.draft(), value.lines(), value.totals(), true)) fail("INVALID_RESPONSE");
            else {
                quotedDraft = snapshot;
                quote = value;
                phase = Phase.QUOTED;
            }
            changed.run();
        });
    }

    public void submit(boolean reviewed, Runnable changed) {
        if (!active() || busy()) return;
        final Attempt attempt;
        var uncertain = unresolvedAttempts.get(context());
        if (uncertain != null) {
            if (!retryPossible()) return;
            attempt = uncertain;
        } else {
            if (quote() == null || hasIngredientConflict() || (requiresAllergyAcknowledgement() && !reviewed)) return;
            attempt = new Attempt(quotedDraft, quote, UUID.randomUUID());
        }
        var context = context();
        phase = Phase.SENDING;
        problem = "";
        diagnostics = List.of();
        changed.run();
        complete(() -> service.submit(attempt.snapshot().draft(), attempt.quote().id(), attempt.key()), (value, error) -> {
            if (error == null && identifies(attempt, value)) {
                accepted = value;
                unresolvedAttempts.remove(context);
                quote = null;
                if (sameSession(value.sessionId(), value.mode())) {
                    customer.cart().removeSubmitted(attempt.snapshot().lines());
                }
                phase = Phase.ACCEPTED;
                refreshAccepted(value, changed);
            } else if (error == null || (error.retrySameKey() && !"IDEMPOTENCY_CONFLICT".equals(error.code()))) {
                unresolvedAttempts.put(context, attempt);
                problem = error == null ? "INVALID_RESPONSE" : error.code();
                problemContext = context;
                phase = Phase.RETRY;
            } else {
                unresolvedAttempts.remove(context);
                quote = null;
                if ("IDEMPOTENCY_CONFLICT".equals(error.code())) conflicts.put(context, attempt.snapshot().draft());
                fail(error, attempt.snapshot());
            }
            changed.run();
        });
    }

    private void refreshAccepted(Ordering.Accepted original, Runnable changed) {
        complete(() -> service.order(original.sessionId(), original.id()), (latest, error) -> {
            if (error == null && latest != null && accepted == original && sameSession(latest.sessionId(), latest.mode())
                    && original.id().equals(latest.id())) {
                accepted = latest;
                changed.run();
            }
        });
    }

    private static boolean identifies(Attempt attempt, Ordering.Accepted value) {
        var draft = attempt.snapshot().draft();
        if (value == null || value.id() == null || value.status() == null || value.acceptedAt() == null
                || !draft.sessionId().equals(value.sessionId()) || draft.mode() != value.mode()
                || !draft.visitAllergy().equals(value.visitAllergy())) return false;
        var ids = new HashSet<UUID>();
        for (var line : value.lines()) {
            if (line.id() == null || !ids.add(line.id()) || line.review() == null || line.preparation() == null) return false;
            var original = draft.lines().stream().filter(item -> item.clientLineId().equals(line.pricing().clientLineId()))
                    .findFirst().orElse(null);
            if (original == null || !original.allergy().equals(line.allergy())
                    || !original.instructions().equals(line.instructions())) return false;
        }
        var prices = value.lines().stream().map(Ordering.AcceptedLine::pricing).toList();
        if (!new HashSet<>(prices).equals(new HashSet<>(attempt.quote().lines()))) return false;
        if (value.totals() == null || (value.lines().stream().allMatch(Ordering.AcceptedLine::billable)
                && !value.totals().equals(attempt.quote().totals()))
                || value.totals().totalMinor() > attempt.quote().totals().totalMinor()) return false;
        return validPricing(draft, prices, value.totals(), false)
                && value.totals().subtotalMinor() == value.lines().stream().filter(Ordering.AcceptedLine::billable)
                .mapToLong(line -> line.pricing().extendedMinor()).sum();
    }

    private static boolean validPricing(Ordering.Draft draft, List<Ordering.Price> prices,
                                        Ordering.Totals totals, boolean quote) {
        if (prices.size() != draft.lines().size() || totals == null || !"KES".equals(totals.currency())
                || totals.subtotalMinor() < 0 || totals.serviceMinor() < 0 || totals.taxMinor() < 0
                || totals.totalMinor() != totals.subtotalMinor() + totals.serviceMinor() + totals.taxMinor()) return false;
        var seen = new HashSet<UUID>();
        for (var price : prices) {
            if (!seen.add(price.clientLineId()) || price.baseUnitMinor() < 0 || price.extendedMinor() < 0) return false;
            var line = draft.lines().stream().filter(item -> item.clientLineId().equals(price.clientLineId()))
                    .findFirst().orElse(null);
            if (line == null || !line.menuItemId().equals(price.menuItemId().toString())
                    || line.quantity() != price.quantity()) return false;
            if (quote) {
                var selections = new HashSet<String>();
                line.selections().forEach((group, options) -> options.forEach(option -> selections.add(group + "/" + option)));
                for (var option : price.options()) {
                    if (!selections.remove(option.groupId() + "/" + option.optionId())) return false;
                }
                if (!selections.isEmpty()) return false;
            }
            long unit = price.baseUnitMinor() + price.options().stream().mapToLong(Ordering.Option::unitAdjustmentMinor).sum();
            if (unit < 0 || price.extendedMinor() != unit * price.quantity()) return false;
        }
        return !quote || prices.stream().mapToLong(Ordering.Price::extendedMinor).sum() == totals.subtotalMinor();
    }

    public static Ordering.Draft draftForEstimate(CustomerState customer) {
        return snapshot(customer).draft();
    }

    private Snapshot snapshot() {
        return snapshot(customer);
    }

    private static Snapshot snapshot(CustomerState customer) {
        var session = customer.terminalSession();
        var lines = customer.cart().lines();
        if (lines.size() > 100) throw new IllegalArgumentException("Too many order lines");
        var items = lines.stream().map(line -> {
            var name = line.item().name().in(customer.locale());
            validateId(line.item().id(), name);
            for (var group : line.item().customizationGroups()) {
                var selected = line.dish().selections().getOrDefault(group.id(), java.util.Set.of());
                for (var option : group.choices()) {
                    if (!selected.contains(option.id())) continue;
                    var description = name + " / " + group.name().in(customer.locale()) + " / " + option.name().in(customer.locale());
                    validateId(group.id(), description);
                    validateId(option.id(), description);
                }
            }
            return new Ordering.Line(line.id(), line.item().id(), line.quantity(), line.dish().selections(),
                    line.dish().allergy(), line.dish().instructions());
        }).toList();
        return new Snapshot(new Ordering.Draft(canonicalId(session.sessionId()), session.mode(),
                visitForWire(customer), items), lines);
    }

    private static com.roseroots.customer.models.AllergyDeclaration visitForWire(CustomerState customer) {
        var declaration = customer.sessionAllergy();
        return declaration.disclosed() || declaration.none() ? declaration
                : new com.roseroots.customer.models.AllergyDeclaration(true, java.util.Set.of(), "");
    }

    private boolean sameSession(UUID id, com.roseroots.customer.models.ServiceMode mode) {
        var session = customer.terminalSession();
        return session != null && session.canSubmitOrders() && session.mode() == mode
                && id.toString().equals(session.sessionId());
    }

    private boolean matches(Snapshot snapshot) {
        if (snapshot == null || !sameSession(snapshot.draft().sessionId(), snapshot.draft().mode())
                || !Objects.equals(snapshot.draft().visitAllergy(), visitForWire(customer))) return false;
        var current = customer.cart().lines();
        if (current.size() != snapshot.lines().size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i) != snapshot.lines().get(i)) return false;
        }
        return true;
    }

    private static void validateId(String id, String name) {
        try { canonicalId(id); }
        catch (IllegalArgumentException invalid) { throw new InvalidReference(name + " (" + id + ")"); }
    }

    private static final class InvalidReference extends IllegalArgumentException {
        InvalidReference(String detail) { super(detail); }
    }

    private void fail(OrderService.Problem error, Snapshot snapshot) {
        fail(error.code());
        diagnostics = error.violations().stream().limit(12).map(violation -> {
            var pointer = violation.pointer().split("/");
            String label = violation.pointer();
            if (pointer.length > 2 && "lines".equals(pointer[1])) {
                try {
                    var line = snapshot.lines().get(Integer.parseInt(pointer[2]));
                    label = line.item().name().in(customer.locale()) + " (" + violation.pointer() + ")";
                    if (pointer.length >= 5 && "selections".equals(pointer[3])) {
                        label = selectionLabel(line, pointer, label);
                    }
                } catch (IndexOutOfBoundsException | NumberFormatException ignored) {
                    // The server pointer remains visible if it cannot be resolved.
                }
            }
            return label.isBlank() ? violation.message() : label + " · " + violation.message();
        }).toList();
    }

    private String selectionLabel(CartDraft.Line line, String[] pointer, String fallback) {
        var selectedGroups = line.dish().selections().entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty()).sorted(java.util.Map.Entry.comparingByKey()).toList();
        int index = Integer.parseInt(pointer[4]);
        if (index < 0 || index >= selectedGroups.size()) return fallback;
        var selected = selectedGroups.get(index);
        for (var group : line.item().customizationGroups()) {
            if (!group.id().equals(selected.getKey())) continue;
            String result = fallback + " / " + group.name().in(customer.locale());
            if (pointer.length >= 7 && "optionIds".equals(pointer[5])) {
                var optionIds = selected.getValue().stream().sorted().toList();
                int optionIndex = Integer.parseInt(pointer[6]);
                if (optionIndex >= 0 && optionIndex < optionIds.size()) {
                    String id = optionIds.get(optionIndex);
                    var option = group.choices().stream().filter(choice -> choice.id().equals(id)).findFirst();
                    if (option.isPresent()) result += " / " + option.get().name().in(customer.locale());
                }
            }
            return result;
        }
        return fallback;
    }

    private void fail(String code) {
        diagnostics = List.of();
        phase = Phase.ERROR;
        problem = code;
        problemContext = context();
    }

    private <T> void complete(java.util.function.Supplier<CompletionStage<T>> request,
                              java.util.function.BiConsumer<T, OrderService.Problem> callback) {
        try {
            request.get().whenComplete((value, error) -> ui.execute(() -> callback.accept(value, asProblem(error))));
        } catch (RuntimeException error) {
            ui.execute(() -> callback.accept(null, asProblem(error)));
        }
    }

    private OrderService.Problem asProblem(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error == null) return null;
        return error instanceof OrderService.Problem problem ? problem : new OrderService.Problem(
                "CONNECTION_INTERRUPTED", phase == Phase.SENDING);
    }

    private record Snapshot(Ordering.Draft draft, List<CartDraft.Line> lines) {}
    private record Attempt(Snapshot snapshot, Ordering.Quote quote, UUID key) {}
}
