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

    private record PendingAdvance(long ticketId, Item item) {}

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
    @Override public boolean writesAvailable() {
        return !stopped && status == Status.CONNECTED && (!busy || quietRead && pendingAdvance == null);
    }
    @Override public List<Ticket> activeTickets() { return tickets; }

    @Override
    public List<Ticket> historyTickets() {
        return List.of();
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
                    && current.status() == queued.item().status() && current.actionable()) {
                save(queued.ticketId(), current);
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
            pendingAdvance = new PendingAdvance(ticketId, item);
            message = "Checking the latest item version before saving…";
            notifyListeners();
            return;
        }
        save(ticketId, item);
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
        throw new UnsupportedOperationException("Rush changes are unavailable in connected mode.");
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
        String suffix = cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return send(ORDERS + suffix, null).thenCompose(raw -> {
            KitchenTicketJson.Page page = parse(() -> json.readPage(raw));
            for (Ticket ticket : page.tickets()) {
                if (!ids.add(ticket.server().id()) || !Ticket.Status.LANES.contains(ticket.status())) {
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
            return fetchPage(next, complete, cursors, ids);
        });
    }

    private CompletableFuture<String> send(String path, JsonObject body) {
        if (stopped) {
            return CompletableFuture.failedFuture(new Failure(0, ""));
        }
        try {
            String token = credentials.bearerToken().orElse("");
            if (token.isBlank() || !token.matches("[A-Za-z0-9._~+/\\-]+=*")) {
                return CompletableFuture.failedFuture(new Failure(401, ""));
            }
            var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json, application/problem+json");
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()));
            }
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> {
                        if (error != null) {
                            throw new Failure(0, "");
                        }
                        if (response.statusCode() != 200) {
                            String code = "";
                            try {
                                code = KitchenTicketJson.object(response.body()).get("code").getAsString();
                            } catch (RuntimeException ignored) {
                                // HTTP status remains useful when a Problem body is absent.
                            }
                            throw new Failure(response.statusCode(), code);
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

        private Failure(int status, String code) {
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
                    default -> "The server rejected the current item state.";
                };
                case 429 -> "The server is rate limiting requests.";
                default -> "The server rejected or could not complete the request (HTTP " + status + ").";
            });
            httpStatus = status;
        }

        boolean offline() { return httpStatus == 0; }
        boolean accessDenied() { return httpStatus == 401 || httpStatus == 403; }
        boolean uncertain() { return httpStatus <= 0 || httpStatus >= 500; }
    }
}
