package com.roseroots.customer.state;

import com.roseroots.customer.services.AssistanceService;
import com.roseroots.customer.services.AssistanceService.*;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Session-scoped state. Mutations and asynchronous completions run on the UI thread. */
public final class AssistanceState {
    public enum Outcome { IDLE, SENDING, UNCERTAIN, REJECTED, SENT }

    private final AssistanceService service;
    private final Clock clock;
    private final Executor ui;
    private final Map<Context, Visit> visits = new HashMap<>();

    public AssistanceState(AssistanceService service, Clock clock, Executor ui) {
        this.service = service;
        this.clock = clock;
        this.ui = ui;
    }

    public Visit visit(Context context) {
        return context == null ? new Visit(null) : visits.computeIfAbsent(context, Visit::new);
    }

    public final class Visit {
        private final Context context;
        private final Map<String, Request> requests = new HashMap<>();
        private Command pending;
        private Outcome outcome = Outcome.IDLE;
        private Connection connection = Connection.DISCONNECTED;
        private Watch watcher;
        private Runnable listener = () -> {};
        private long generation;

        private Visit(Context context) { this.context = context; }

        public Outcome outcome() { return outcome; }
        public Connection connection() { return connection; }

        public List<Request> requests() {
            return requests.values().stream().sorted(Comparator.comparing(Request::submittedAt).reversed()
                    .thenComparing(Request::id)).toList();
        }

        public boolean duplicate(Type type) {
            return requests.values().stream().anyMatch(request -> request.type() == type
                    && request.status() != Status.COMPLETED);
        }

        public boolean canSend(Type type) {
            return context != null && service.available() && connection == Connection.LIVE
                    && pending == null && type != null && !duplicate(type);
        }

        public boolean canRetry() {
            return service.available() && connection == Connection.LIVE && outcome == Outcome.UNCERTAIN;
        }

        public Watch observe(Runnable changed) {
            stopWatching();
            listener = changed;
            long current = generation;
            if (context != null && service.available()) {
                connection = Connection.LOADING;
                try {
                    watcher = service.watch(context, update -> ui.execute(() -> {
                        if (current != generation) return;
                        connection = update.connection();
                        if (connection == Connection.LIVE) {
                            update.requests().stream().filter(request -> context.equals(request.context())).forEach(this::merge);
                        }
                        listener.run();
                    }));
                } catch (RuntimeException failure) {
                    connection = Connection.ERROR;
                }
            } else {
                connection = Connection.DISCONNECTED;
            }
            listener.run();
            return () -> {
                if (current == generation) stopWatching();
            };
        }

        private void stopWatching() {
            generation++;
            listener = () -> {};
            if (watcher != null) watcher.close();
            watcher = null;
        }

        public void send(Type type, String message) {
            if (!canSend(type)) return;
            pending = new Command(context, UUID.randomUUID(), type, message, clock.instant());
            transmit();
        }

        public void retry() {
            if (canRetry()) transmit();
        }

        private void transmit() {
            outcome = Outcome.SENDING;
            var command = pending;
            listener.run();
            try {
                service.send(command).whenComplete((request, failure) ->
                        ui.execute(() -> completed(command, request, failure)));
            } catch (RuntimeException failure) {
                completed(command, null, failure);
            }
        }

        private void completed(Command command, Request request, Throwable failure) {
            if (pending != command) return;
            while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof AssistanceService.Rejected) {
                pending = null;
                outcome = Outcome.REJECTED;
            } else if (failure == null && request != null && context.equals(request.context())
                    && command.type() == request.type() && command.message().equals(request.message())) {
                merge(request);
                pending = null;
                outcome = Outcome.SENT;
            } else {
                outcome = Outcome.UNCERTAIN;
            }
            listener.run();
        }

        private void merge(Request request) {
            requests.merge(request.id(), request, (previous, next) ->
                    !next.updatedAt().isBefore(previous.updatedAt()) && next.version() >= previous.version()
                            && next.status().ordinal() >= previous.status().ordinal()
                            ? next : previous);
        }
    }
}
