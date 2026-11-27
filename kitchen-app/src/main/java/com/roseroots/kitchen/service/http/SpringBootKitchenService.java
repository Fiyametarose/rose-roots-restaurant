package com.roseroots.kitchen.service.http;

import com.google.gson.JsonObject;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.service.KitchenService;
import com.roseroots.kitchen.service.KitchenSync;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import com.roseroots.kitchen.model.CustomerReview.Topic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class SpringBootKitchenService implements KitchenService, KitchenSync {
    private static final String ORDERS = "/api/v1/kitchen/orders?limit=50";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private final URI origin;
    private final StaffCredentialProvider credentials;
    private final HttpClient client;
    private final Duration timeout;
    private final Consumer<Runnable> dispatcher;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, Long> handles = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong();
    private final KitchenTicketJson json = new KitchenTicketJson(id ->
            handles.computeIfAbsent(id, key -> nextHandle.incrementAndGet()));
    private volatile List<Ticket> tickets = List.of();
    private volatile Status status = Status.OFFLINE;
    private volatile String message = "";
    private volatile boolean busy;
    private volatile boolean quietRead;
    private volatile boolean stopped;
    private final Duration pollInterval;
    private final Duration maxBackoff;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("kitchen-ticket-sync").factory());
    private boolean automatic;
    private long scheduleVersion;
    private ScheduledFuture<?> nextPoll;
    private int readFailures;
    private volatile PendingAdvance pendingAdvance;
    private volatile StaffSession staff;
    private volatile StaffSession dutyActor;
    private volatile boolean onDuty;

    public boolean waiterOnDuty() {
        var actor = staff;
        return actor != null && actor == dutyActor && "WAITER".equals(actor.role()) && onDuty;
    }

    public void displayDuty(StaffSession actor, boolean active) {
        if (actor != staff || actor == dutyActor && active == onDuty) return;
        onDuty = active;
        dutyActor = actor;
        notifyListeners();
    }

    public record StaffSession(UUID id, String username, String role, boolean allergyReviewer,
                               java.time.Instant expiresAt, String token) {
        @Override public String toString() { return username; }
    }

    private record PendingAdvance(long ticketId, Item item, Boolean approval, String reason) {}

    public SpringBootKitchenService(KitchenBackendConfig config, StaffCredentialProvider credentials,
                                    Consumer<Runnable> dispatcher) {
        this(config.origin(), credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(12), dispatcher);
        if (config.mode() != KitchenBackendConfig.Mode.CONNECTED
                || config.origin() == null || !"https".equalsIgnoreCase(config.origin().getScheme())) {
            throw new IllegalArgumentException("Connected Kitchen mode requires HTTPS.");
        }
    }

    // Package-private transport injection for controlled HTTP fixtures.
    SpringBootKitchenService(URI origin, StaffCredentialProvider credentials, HttpClient client,
                             Duration timeout, Consumer<Runnable> dispatcher) {
        this(origin, credentials, client, timeout, dispatcher, POLL_INTERVAL, MAX_BACKOFF);
    }

    SpringBootKitchenService(URI origin, StaffCredentialProvider credentials, HttpClient client,
                             Duration timeout, Consumer<Runnable> dispatcher, Duration pollInterval, Duration maxBackoff) {
        this.origin = origin;
        this.credentials = credentials;
        this.client = client;
        this.timeout = timeout;
        this.dispatcher = dispatcher;
        this.pollInterval = pollInterval;
        this.maxBackoff = maxBackoff;
    }

    @Override public boolean local() { return false; }
    @Override public Status status() { return status; }
    @Override public String message() { return message; }
    @Override public boolean busy() { return busy && !quietRead; }
    public boolean workstation() { return credentials instanceof WorkstationCredential; }
    public StaffSession signedInStaff() { return staff; }
    public boolean canConfirmHandoff() {
        return waiterOnDuty() && writesAvailable();
    }
    @Override public boolean writesAvailable() {
        return (!workstation() || staff != null) && !stopped && status == Status.CONNECTED
                && (!busy || quietRead && pendingAdvance == null);
    }

    public CompletableFuture<StaffSession> signIn(String username, String password) {
        var body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        return exchange("/api/v1/kitchen/staff-session", body, "POST", null, 201).thenApply(raw -> {
            var data = KitchenTicketJson.object(raw);
            var session = new StaffSession(UUID.fromString(data.get("id").getAsString()), data.get("username").getAsString(),
                    data.get("role").getAsString(), data.get("allergyReviewer").getAsBoolean(),
                    java.time.Instant.parse(data.get("expiresAt").getAsString()), data.get("sessionToken").getAsString());
            synchronized (this) {
                if (stopped) return session;
                staff = session;
                long delay = Math.max(0, java.time.Duration.between(java.time.Instant.now(), session.expiresAt()).toMillis());
                scheduler.schedule(() -> lockIfCurrent(session), delay, TimeUnit.MILLISECONDS);
                notifyListeners();
            }
            return session;
        });
    }

    public synchronized void lockStaff() {
        lockIfCurrent(staff);
    }

    private synchronized void lockIfCurrent(StaffSession session) {
        if (session == null || staff != session) return;
        staff = null;
        pendingAdvance = null;
        exchange("/api/v1/kitchen/staff-session", null, "DELETE", session, 204);
        notifyListeners();
    }

    public void ownership(Item item, String action, UUID chefId, String reason) {
        var session = staff;
        if (session == null) return;
        var body = new JsonObject();
        body.addProperty("action", action);
        body.addProperty("expectedVersion", item.server().version());
        if (chefId != null) {
            body.addProperty("chefId", chefId.toString());
            body.addProperty("reason", reason);
        }
        exchange("/api/v1/kitchen/items/" + item.server().id() + "/ownership", body, "POST", session, 200)
                .whenComplete((response, error) -> {
                    synchronized (this) {
                        if (stopped) return;
                        message = error == null ? "Item ownership saved." : failure(error).getMessage();
                        if (!busy) read(true);
                        notifyListeners();
                    }
                });
    }

    public CompletableFuture<JsonObject> preparationDetails(Item item, boolean candidates) {
        var session = staff;
        return exchange("/api/v1/kitchen/items/" + item.server().id()
                        + (candidates ? "/eligible-chefs" : "/preparation-history"), null, "GET", session, 200)
                .thenApply(KitchenTicketJson::object);
    }
    @Override public List<Ticket> activeTickets() { return tickets; }

    @Override
    public List<Ticket> historyTickets() {
        return List.of();
    }

    public CompletableFuture<List<Ticket>> loadHistory() {
        return fetchPage("/api/v1/kitchen/history?limit=50", null, new ArrayList<>(), new HashSet<>(), new HashSet<>());
    }

    @Override
    public Runnable onOrdersChanged(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public synchronized void refresh() {
        if (!stopped && !busy) {
            read(false);
        }
    }

    public synchronized void startAutomaticRefresh() {
        if (automatic || stopped) {
            return;
        }
        automatic = true;
        refresh();
    }

    @Override
    public synchronized void stop() {
        lockStaff();
        if (stopped) {
            return;
        }
        stopped = true;
        busy = false;
        quietRead = false;
        pendingAdvance = null;
        cancelPoll();
        listeners.clear();
        scheduler.shutdownNow();
        client.shutdownNow();
    }

    private void read(boolean background) {
        cancelPoll();
        quietRead = background && status == Status.CONNECTED;
        if (background) {
            busy = true;
        } else {
            begin(Status.LOADING, "Loading the active ticket snapshot…");
        }
        fetchSnapshot().whenComplete((snapshot, error) -> completeRead(background, snapshot, error));
    }

    private synchronized void completeRead(boolean background, List<Ticket> snapshot, Throwable error) {
        if (stopped) {
            return;
        }
        boolean wasQuiet = quietRead;
        quietRead = false;
        PendingAdvance queued = pendingAdvance;
        pendingAdvance = null;
        if (error != null) {
            readFailed(failure(error), queued == null ? "" : "The preparation change was not sent. ");
            return;
        }
        readFailures = 0;
        boolean changed = !tickets.equals(snapshot);
        if (changed) {
            tickets = snapshot;
        }
        if (background && queued != null) {
            Item current = findItem(queued.ticketId(), queued.item().id());
            if (current != null && current.server().version() == queued.item().server().version()
                    && current.status() == queued.item().status()
                    && (queued.approval() == null ? current.actionable() : held(current))) {
                if (queued.approval() == null) {
                    save(queued.ticketId(), current);
                } else {
                    saveReview(queued.ticketId(), current, queued.approval(), queued.reason());
                }
            } else {
                finish(Status.CONNECTED, "The item changed during synchronization. Review the current ticket.");
            }
        } else if (wasQuiet && !changed) {
            busy = false;
            schedulePoll();
        } else {
            finish(Status.CONNECTED, wasQuiet ? message : "");
        }
    }

    @Override
    public synchronized void advanceItem(long ticketId, long itemId, Ticket.Status expected) {
        advanceItem(ticketId, itemId, expected, null);
    }

    @Override
    public synchronized void advanceItem(long ticketId, Item displayedItem) {
        advanceItem(ticketId, displayedItem.id(), displayedItem.status(), displayedItem.server().version());
    }

    private void advanceItem(long ticketId, long itemId, Ticket.Status expected, Long expectedVersion) {
        if (!writesAvailable()) {
            return;
        }
        Item item = findItem(ticketId, itemId);
        if (item == null || item.status() != expected || !item.actionable()
                || expectedVersion != null && item.server().version() != expectedVersion) {
            message = "This item is no longer eligible. Review the current ticket.";
            notifyListeners();
            return;
        }
        if (quietRead) {
            pendingAdvance = new PendingAdvance(ticketId, item, null, "");
            message = "Checking the latest item version before saving…";
            notifyListeners();
            return;
        }
        save(ticketId, item);
    }

    @Override
    public synchronized void reviewAllergy(long ticketId, Item displayed, boolean approve, String reason) {
        if (!writesAvailable()) {
            return;
        }
        Item item = findItem(ticketId, displayed.id());
        if (item == null || !held(item) || item.server().version() != displayed.server().version()) {
            message = "The held item changed. Review its current state.";
            notifyListeners();
            return;
        }
        if (quietRead) {
            pendingAdvance = new PendingAdvance(ticketId, item, approve, reason);
            message = "Checking the latest item version before recording the review…";
            notifyListeners();
            return;
        }
        saveReview(ticketId, item, approve, reason);
    }

    private static boolean held(Item item) {
        return item.status() == Ticket.Status.RECEIVED
                && item.server().allergyReviewStatus().equals("PENDING_ALLERGY_REVIEW");
    }

    private void saveReview(long ticketId, Item item, boolean approve, String reason) {
        String decision = approve ? "APPROVE" : "DECLINE";
        String review = approve ? "APPROVED_FOR_PREPARATION" : "DECLINED";
        Ticket.Status statusAfter = approve ? Ticket.Status.RECEIVED : Ticket.Status.CANCELLED;
        JsonObject body = new JsonObject();
        body.addProperty("decision", decision);
        body.addProperty("reason", reason);
        body.addProperty("expectedVersion", item.server().version());
        cancelPoll();
        Ticket ticket = tickets.stream().filter(order -> order.id() == ticketId).findFirst().orElseThrow();
        begin(Status.CONNECTED, "Recording allergy review…");
        send("/api/v1/kitchen/items/" + item.server().id() + "/allergy-review", body, "POST")
                .thenApply(raw -> {
                    Ticket updated = parse(() -> json.ticket(KitchenTicketJson.object(raw)));
                    if (!updated.server().id().equals(ticket.server().id())
                            || updated.server().version() <= ticket.server().version()
                            || !updated.items().stream().anyMatch(line -> line.server().id().equals(item.server().id())
                                    && line.server().version() > item.server().version()
                                    && line.status() == statusAfter && line.server().allergyReviewStatus().equals(review))) {
                        throw unreadable();
                    }
                    return rejectOlderVersions(List.of(ticket), List.of(updated)).getFirst();
                }).whenComplete((updated, error) -> completeAction(ticketId, updated, error, true));
    }

    @Override
    public synchronized void handoff(Ticket displayed) {
        if (!canConfirmHandoff()) return;
        if (busy) {
            message = "Wait for the current synchronization before confirming handoff.";
            notifyListeners();
            return;
        }
        var current = tickets.stream().filter(ticket -> ticket.id() == displayed.id()).findFirst();
        if (current.isEmpty() || current.get().server().version() != displayed.server().version()
                || current.get().status() != Ticket.Status.READY) {
            message = "The order changed. Review the current ticket before confirming handoff.";
            notifyListeners();
            return;
        }
        var target = displayed.type() == Ticket.Type.DINE_IN ? Ticket.Status.SERVED : Ticket.Status.COLLECTED;
        var body = new JsonObject();
        body.addProperty("status", target.name());
        body.addProperty("expectedVersion", displayed.server().version());
        cancelPoll();
        begin(Status.CONNECTED, "Confirming handoff…");
        send("/api/v1/kitchen/orders/" + displayed.server().id() + "/handoff", body, "POST")
                .thenApply(raw -> {
                    var result = parse(() -> json.ticket(KitchenTicketJson.object(raw)));
                    if (!result.server().id().equals(displayed.server().id())
                            || result.server().version() <= displayed.server().version() || result.status() != target) {
                        throw unreadable();
                    }
                    return rejectOlderVersions(List.of(displayed), List.of(result)).getFirst();
                }).whenComplete((result, error) -> completeAction(displayed.id(), result, error, false));
    }

    private synchronized void completeAction(long ticketId, Ticket updated, Throwable error, boolean review) {
        if (stopped) return;
        if (error == null) {
            tickets = tickets.stream().map(entry -> entry.id() == ticketId ? updated : entry)
                    .filter(entry -> Ticket.Status.LANES.contains(entry.status())).toList();
            finish(Status.CONNECTED, review ? "Allergy review recorded." : "Handoff recorded.");
            return;
        }
        Failure failure = failure(error);
        if (failure.httpStatus == 401 || failure.httpStatus == 429 || failure.httpStatus == 403 && !review) {
            readFailed(failure, "");
            return;
        }
        if (failure.httpStatus != 409 && !failure.uncertain()) {
            finish(Status.CONNECTED, review && failure.httpStatus == 403
                    ? "An authorized Chef credential is required." : failure.getMessage());
            return;
        }
        fetchSnapshot().whenComplete((snapshot, reloadError) -> {
            synchronized (this) {
                if (stopped) return;
                if (reloadError == null) {
                    tickets = snapshot;
                    finish(Status.CONNECTED, failure.getMessage() + " Current tickets reloaded.");
                } else {
                    readFailed(failure(reloadError), "The action outcome could not be confirmed. ");
                }
            }
        });
    }

    private Item findItem(long ticketId, long itemId) {
        return tickets.stream().filter(order -> order.id() == ticketId).flatMap(order -> order.items().stream())
                .filter(line -> line.id() == itemId).findFirst().orElse(null);
    }

    private void save(long ticketId, Item item) {
        cancelPoll();
        Ticket ticket = tickets.stream().filter(order -> order.id() == ticketId).findFirst().orElseThrow();
        Ticket.Status expected = item.status();
        Ticket.Status target = expected.next();
        JsonObject body = new JsonObject();
        body.addProperty("status", target.name());
        body.addProperty("expectedVersion", item.server().version());
        begin(Status.CONNECTED, "Saving item status…");
        send("/api/v1/kitchen/items/" + item.server().id() + "/status", body)
                .thenApply(raw -> {
                    Ticket updated = parse(() -> json.ticket(KitchenTicketJson.object(raw)));
                    if (!updated.server().id().equals(ticket.server().id())
                            || updated.server().version() <= ticket.server().version()
                            || !confirmed(updated, item, target)) {
                        throw unreadable();
                    }
                    return rejectOlderVersions(List.of(ticket), List.of(updated)).getFirst();
                })
                .whenComplete((updated, error) -> completeWrite(ticketId, item, target, updated, error));
    }

    private synchronized void completeWrite(long ticketId, Item item, Ticket.Status target, Ticket updated, Throwable error) {
        if (stopped) {
            return;
        }
        if (error == null) {
            tickets = tickets.stream().map(entry -> entry.id() == ticketId ? updated : entry)
                    .filter(entry -> Ticket.Status.LANES.contains(entry.status())).toList();
            readFailures = 0;
            finish(Status.CONNECTED, "Item status saved.");
        } else {
            handleWriteFailure(ticketId, item, target, failure(error));
        }
    }

    @Override
    public void advance(long ticketId, Station station, Ticket.Status expected) {
        throw new UnsupportedOperationException("Connected preparation updates one item at a time.");
    }

    @Override
    public void changeRush(long ticketId, boolean enabled, String reason) {
        var displayed = tickets.stream().filter(ticket -> ticket.id() == ticketId).findFirst()
                .orElseThrow(() -> new IllegalStateException("Order no longer available."));
        changePriority(displayed, true, enabled, reason);
    }

    public synchronized void changePriority(Ticket displayed, boolean rush, boolean enabled, String reason) {
        String note = reason.strip();
        if (note.isEmpty() || note.length() > 240) throw new IllegalArgumentException("Enter a reason of 1–240 characters.");
        if (stopped || status != Status.CONNECTED || busy) {
            throw new IllegalStateException("Wait for the current synchronization before changing priority.");
        }
        long ticketId = displayed.id();
        var ticket = tickets.stream().filter(entry -> entry.id() == ticketId && entry.server().version() == displayed.server().version()
                && (entry.status() == Ticket.Status.RECEIVED || entry.status() == Ticket.Status.PREPARING))
                .findFirst().orElseThrow(() -> new IllegalStateException("This order can no longer be reprioritized."));
        var body = new JsonObject();
        if (rush) body.addProperty("priority", enabled ? "RUSH" : "NORMAL");
        else body.addProperty("delayed", enabled);
        body.addProperty("reason", note);
        body.addProperty("expectedVersion", ticket.server().version());
        cancelPoll();
        begin(Status.CONNECTED, "Saving priority change…");
        var path = "/api/v1/kitchen/orders/" + ticket.server().id() + (rush ? "/rush" : "/delayed");
        send(path, body, "POST").thenApply(raw -> {
            var updated = parse(() -> json.ticket(KitchenTicketJson.object(raw)));
            if (!updated.server().id().equals(ticket.server().id()) || updated.server().version() <= ticket.server().version()) {
                throw unreadable();
            }
            return rejectOlderVersions(List.of(ticket), List.of(updated)).getFirst();
        }).whenComplete((updated, error) -> finishPriority(ticketId, updated, error));
    }

    private synchronized void finishPriority(long ticketId, Ticket updated, Throwable error) {
        if (stopped) return;
        if (error == null) {
            tickets = tickets.stream().map(ticket -> ticket.id() == ticketId ? updated : ticket).toList();
            finish(Status.CONNECTED, "Priority change recorded.");
            return;
        }
        var failure = failure(error);
        if (failure.httpStatus == 401 || failure.httpStatus == 429) readFailed(failure, "");
        else {
            finish(Status.CONNECTED, failure.httpStatus == 403 ? "A Kitchen Supervisor must change priority."
                    : "The priority change could not be confirmed. Review the latest ticket before retrying.");
        }
    }

    private void handleWriteFailure(long ticketId, Item item, Ticket.Status target, Failure failure) {
        if (failure.httpStatus != 409 && !failure.uncertain()) {
            if (failure.accessDenied() || failure.httpStatus == 429) {
                readFailed(failure, "");
            } else {
                finish(Status.CONNECTED, failure.getMessage());
            }
            return;
        }
        status = Status.LOADING;
        message = failure.getMessage() + " Reloading authoritative tickets…";
        notifyListeners();
        fetchSnapshot().whenComplete((snapshot, error) -> reconcile(ticketId, item, target, failure, snapshot, error));
    }

    private synchronized void reconcile(long ticketId, Item item, Ticket.Status target, Failure failure,
                                        List<Ticket> snapshot, Throwable error) {
        if (stopped) {
            return;
        }
        if (error != null) {
            readFailed(failure(error), "Update could not be reconciled. ");
            return;
        }
        tickets = snapshot;
        readFailures = 0;
        boolean persisted = snapshot.stream().anyMatch(ticket ->
                ticket.id() == ticketId && confirmed(ticket, item, target));
        String result = persisted ? "Current server state confirms the requested status."
                : "Current state reloaded. Review the ticket before taking another action.";
        finish(Status.CONNECTED, failure.getMessage() + " " + result);
    }

    private void readFailed(Failure failure, String prefix) {
        if (failure.personal && failure.accessDenied()) {
            finish(Status.CONNECTED, prefix + failure.getMessage());
            return;
        }
        readFailures = Math.min(readFailures + 1, 20);
        if (failure.accessDenied()) {
            tickets = List.of();
        }
        finish(failure.offline() ? Status.OFFLINE : Status.FAILED, prefix + failure.getMessage());
    }

    private static boolean confirmed(Ticket ticket, Item previous, Ticket.Status target) {
        return ticket.items().stream().anyMatch(item -> item.server().id().equals(previous.server().id())
                && item.server().version() > previous.server().version() && item.status() == target);
    }

    private CompletableFuture<List<Ticket>> fetchSnapshot() {
        List<Ticket> previous = tickets;
        return fetchPage(null, new ArrayList<>(), new HashSet<>(), new HashSet<>())
                .thenApply(snapshot -> rejectOlderVersions(previous, snapshot));
    }

    public CompletableFuture<List<ConnectedAssistanceRequest>> assistanceRequests() {
        return assistancePage(null, new ArrayList<>(), new HashSet<>(), new HashSet<>());
    }

    public record ConnectedReview(UUID id, Instant submittedAt, Integer rating, List<Topic> topics, String comment) {}

    public CompletableFuture<List<ConnectedReview>> reviews() {
        return reviewPage(null, new ArrayList<>(), new HashSet<>(), new HashSet<>());
    }

    public CompletableFuture<JsonObject> foodFeedback(int offset) {
        return send("/api/v1/kitchen/food-feedback?limit=25&offset=" + offset, null).thenApply(KitchenTicketJson::object);
    }

    private CompletableFuture<List<ConnectedReview>> reviewPage(String cursor, List<ConnectedReview> rows,
            HashSet<String> cursors, HashSet<UUID> ids) {
        String suffix = cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return send("/api/v1/kitchen/reviews?limit=50" + suffix, null).thenCompose(raw -> {
            var page = parse(() -> KitchenTicketJson.object(raw));
            for (var element : page.getAsJsonArray("reviews")) {
                var row = element.getAsJsonObject();
                String id = row.get("id").getAsString();
                UUID uuid = UUID.fromString(id);
                Integer rating = row.get("rating").isJsonNull() ? null
                        : row.get("rating").getAsBigDecimal().intValueExact();
                var topics = row.getAsJsonArray("topics").asList().stream()
                        .map(value -> Topic.valueOf(value.getAsString())).toList();
                if (!uuid.toString().equals(id) || !ids.add(uuid) || rating != null && (rating < 1 || rating > 5)
                        || topics.isEmpty() || topics.stream().anyMatch(topic -> !topic.kitchenRelevant)) throw unreadable();
                rows.add(new ConnectedReview(uuid, Instant.parse(row.get("submittedAt").getAsString()), rating, topics,
                        row.get("comment").getAsString()));
            }
            if (page.get("nextCursor").isJsonNull()) return CompletableFuture.completedFuture(List.copyOf(rows));
            String next = page.get("nextCursor").getAsString();
            if (next.isBlank() || next.length() > 500 || !cursors.add(next) || cursors.size() > 2000) throw unreadable();
            return reviewPage(next, rows, cursors, ids);
        });
    }

    public CompletableFuture<JsonObject> staffDuty() {
        return send("/api/v1/kitchen/staff-duty", null).thenApply(KitchenTicketJson::object);
    }

    public CompletableFuture<JsonObject> changeDuty(int hours, long version) {
        var data = new JsonObject();
        data.addProperty("hours", hours);
        data.addProperty("expectedVersion", version);
        return send("/api/v1/kitchen/staff-duty", data).thenApply(KitchenTicketJson::object);
    }

    public CompletableFuture<com.google.gson.JsonArray> eligibleWaiters(ConnectedAssistanceRequest row) {
        return send("/api/v1/kitchen/assistance/" + row.id() + "/eligible-waiters", null)
                .thenApply(raw -> com.google.gson.JsonParser.parseString(raw).getAsJsonArray());
    }

    public CompletableFuture<String> reassignAssistance(ConnectedAssistanceRequest row, UUID waiter, String reason) {
        var data = new JsonObject();
        data.addProperty("waiterId", waiter.toString());
        data.addProperty("expectedVersion", row.version());
        data.addProperty("reason", reason);
        return send("/api/v1/kitchen/assistance/" + row.id() + "/reassign", data, "POST");
    }

    public CompletableFuture<ConnectedAssistanceRequest> advanceAssistance(ConnectedAssistanceRequest displayed) {
        var next = displayed.status().next();
        var body = new JsonObject();
        body.addProperty("status", next.name());
        body.addProperty("expectedVersion", displayed.version());
        return send("/api/v1/kitchen/assistance/" + displayed.id(), body)
                .thenApply(raw -> parse(() -> {
                    var updated = ConnectedAssistanceRequest.parse(KitchenTicketJson.object(raw));
                    if (!updated.id().equals(displayed.id()) || !updated.sessionId().equals(displayed.sessionId())
                            || updated.status() != next || updated.version() != displayed.version() + 1) throw unreadable();
                    return updated;
                }));
    }

    private CompletableFuture<List<ConnectedAssistanceRequest>> assistancePage(String cursor,
            List<ConnectedAssistanceRequest> rows, HashSet<String> cursors, HashSet<UUID> ids) {
        String suffix = cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return send("/api/v1/kitchen/assistance?limit=50" + suffix, null).thenCompose(raw -> {
            var page = parse(() -> KitchenTicketJson.object(raw));
            for (var element : page.getAsJsonArray("requests")) {
                var request = parse(() -> ConnectedAssistanceRequest.parse(element.getAsJsonObject()));
                if (!ids.add(request.id())) throw unreadable();
                rows.add(request);
            }
            if (page.get("nextCursor").isJsonNull()) return CompletableFuture.completedFuture(List.copyOf(rows));
            String next = page.get("nextCursor").getAsString();
            if (next.isBlank() || next.length() > 500 || !cursors.add(next) || cursors.size() > 2000) throw unreadable();
            return assistancePage(next, rows, cursors, ids);
        });
    }

    private static List<Ticket> rejectOlderVersions(List<Ticket> previous, List<Ticket> snapshot) {
        var orderVersions = new HashMap<UUID, Long>();
        var itemVersions = new HashMap<UUID, Long>();
        for (Ticket ticket : previous) {
            orderVersions.put(ticket.server().id(), ticket.server().version());
            ticket.items().forEach(item -> itemVersions.put(item.server().id(), item.server().version()));
        }
        for (Ticket ticket : snapshot) {
            if (ticket.server().version() < orderVersions.getOrDefault(ticket.server().id(), 0L)
                    || ticket.items().stream().anyMatch(item ->
                    item.server().version() < itemVersions.getOrDefault(item.server().id(), 0L))) {
                throw new Failure(-2, "");
            }
        }
        return snapshot;
    }

    private CompletableFuture<List<Ticket>> fetchPage(String cursor, List<Ticket> complete,
                                                      HashSet<String> cursors, HashSet<UUID> ids) {
        return fetchPage(ORDERS, cursor, complete, cursors, ids);
    }

    private CompletableFuture<List<Ticket>> fetchPage(String endpoint, String cursor, List<Ticket> complete,
                                                      HashSet<String> cursors, HashSet<UUID> ids) {
        String suffix = cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return send(endpoint + suffix, null).thenCompose(raw -> {
            KitchenTicketJson.Page page = parse(() -> json.readPage(raw));
            for (Ticket ticket : page.tickets()) {
                if (!ids.add(ticket.server().id()) || (endpoint.equals(ORDERS)
                        ? !Ticket.Status.LANES.contains(ticket.status()) : ticket.completion() == null)) {
                    throw unreadable();
                }
                complete.add(ticket);
            }
            String next = page.nextCursor();
            if (next == null) {
                return CompletableFuture.completedFuture(List.copyOf(complete));
            }
            if (next.isBlank() || next.length() > 500 || !cursors.add(next) || cursors.size() > 2000) {
                throw unreadable();
            }
            return fetchPage(endpoint, next, complete, cursors, ids);
        });
    }

    private CompletableFuture<String> send(String path, JsonObject body) {
        return send(path, body, "PATCH");
    }

    private CompletableFuture<String> send(String path, JsonObject body, String method) {
        boolean personalRead = path.startsWith("/api/v1/kitchen/history?") || path.startsWith("/api/v1/kitchen/reviews?")
                || path.startsWith("/api/v1/kitchen/food-feedback?");
        boolean dutyRead = path.equals("/api/v1/kitchen/staff-duty") || path.endsWith("/eligible-waiters");
        boolean waiterQueue = path.startsWith("/api/v1/kitchen/assistance?") && staff != null
                && "WAITER".equals(staff.role());
        var session = personalRead || dutyRead || waiterQueue || body != null ? staff : null;
        return exchange(path, body, body == null ? "GET" : method, session, 200);
    }

    private CompletableFuture<String> exchange(String path, JsonObject body, String method, StaffSession personal,
                                               int expectedStatus) {
        if (stopped) {
            return CompletableFuture.failedFuture(new Failure(0, ""));
        }
        try {
            String token = personal == null ? credentials.bearerToken().orElse("") : personal.token();
            if (token.isBlank() || !token.matches("[A-Za-z0-9._~+/\\-]+=*")) {
                return CompletableFuture.failedFuture(new Failure(401, ""));
            }
            var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json, application/problem+json");
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> {
                        if (error != null) {
                            throw new Failure(0, "");
                        }
                        if (response.statusCode() != expectedStatus) {
                            if (response.statusCode() == 401 && personal != null && expectedStatus != 204) lockIfCurrent(personal);
                            String code = "";
                            try {
                                code = KitchenTicketJson.object(response.body()).get("code").getAsString();
                            } catch (RuntimeException ignored) {
                                // HTTP status remains useful when a Problem body is absent.
                            }
                            throw new Failure(response.statusCode(), code, personal != null);
                        }
                        return response.body();
                    });
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(new Failure(401, ""));
        }
    }

    private static <T> T parse(java.util.function.Supplier<T> reader) {
        try {
            return reader.get();
        } catch (RuntimeException ex) {
            throw unreadable();
        }
    }

    private static Failure unreadable() {
        return new Failure(-1, "");
    }

    private static Failure failure(Throwable error) {
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        return cause instanceof Failure failure ? failure : unreadable();
    }

    private void begin(Status next, String detail) {
        busy = true;
        status = next;
        message = detail;
        notifyListeners();
    }

    private void finish(Status next, String detail) {
        status = next;
        message = detail;
        busy = false;
        schedulePoll();
        notifyListeners();
    }

    private void schedulePoll() {
        if (!automatic || stopped) {
            return;
        }
        cancelPoll();
        long generation = scheduleVersion;
        long delay = Math.min(maxBackoff.toMillis(), pollInterval.toMillis() * (1L << readFailures));
        nextPoll = scheduler.schedule(() -> poll(generation), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void poll(long generation) {
        if (generation == scheduleVersion && !stopped && !busy) {
            read(true);
        }
    }

    private void cancelPoll() {
        scheduleVersion++;
        if (nextPoll != null) {
            nextPoll.cancel(false);
            nextPoll = null;
        }
    }

    private void notifyListeners() {
        dispatcher.accept(() -> {
            if (!stopped) {
                listeners.forEach(Runnable::run);
            }
        });
    }

    private static final class Failure extends RuntimeException {
        private final int httpStatus;
        private final boolean personal;

        private Failure(int status, String code) {
            this(status, code, false);
        }

        private Failure(int status, String code, boolean personal) {
            super(switch (status) {
                case -2 -> "The server returned older ticket versions. Keeping the last synchronized tickets.";
                case -1 -> "The server returned an unexpected ticket response.";
                case 0 -> "The server could not be reached or timed out.";
                case 401 -> "Staff authentication is required or expired.";
                case 403 -> "The server denied staff access.";
                case 409, 422 -> switch (code) {
                    case "REVIEW_REQUIRED" -> "Allergy review is required before preparation.";
                    case "INVALID_TRANSITION" -> "The server rejected the preparation transition.";
                    case "VERSION_CONFLICT" -> "The ticket version changed.";
                    case "ITEM_ALREADY_CLAIMED" -> "Another Chef already claimed this item.";
                    case "CHEF_NOT_AVAILABLE" -> "The Chef must be on duty and assigned to this station.";
                    default -> "The server rejected the current item state.";
                };
                case 429 -> "The server is rate limiting requests.";
                default -> "The server rejected or could not complete the request (HTTP " + status + ").";
            });
            httpStatus = status;
            this.personal = personal;
        }

        boolean offline() { return httpStatus == 0; }
        boolean accessDenied() { return httpStatus == 401 || httpStatus == 403; }
        boolean uncertain() { return httpStatus <= 0 || httpStatus >= 500; }
    }
}
