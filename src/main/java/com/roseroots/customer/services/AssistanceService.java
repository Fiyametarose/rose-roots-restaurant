package com.roseroots.customer.services;

import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.ServiceMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface AssistanceService {
    enum Type { WAITER, DRINKING_WATER, CUTLERY, NAPKINS, ORDER_HELP, BILL, OTHER }
    enum Status { SUBMITTED, ACKNOWLEDGED, IN_PROGRESS, COMPLETED }
    enum Connection { LOADING, LIVE, DISCONNECTED, RECONNECTING, ERROR }

    record Context(UUID session, String device, ServiceMode mode, String destination) {
        public static Context from(TerminalSession session) {
            if (session == null || !session.canSubmitOrders()) return null;
            try {
                return new Context(Ordering.canonicalId(session.sessionId()), session.device().deviceId(), session.mode(),
                        session.mode() == ServiceMode.DINE_IN ? session.device().table().displayNumber() : session.pickupReference());
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    record Command(Context context, UUID key, Type type, String message, Instant requestedAt) {
        public Command {
            Objects.requireNonNull(context);
            Objects.requireNonNull(key);
            Objects.requireNonNull(type);
            Objects.requireNonNull(requestedAt);
            message = Objects.requireNonNull(message).strip();
            if (message.length() > 240 || (type != Type.OTHER && !message.isEmpty())) {
                throw new IllegalArgumentException("Invalid assistance message");
            }
        }
    }

    record Request(String id, Context context, Type type, String message, Instant submittedAt,
                   Instant updatedAt, Status status) {
        public Request {
            if (id == null || id.isBlank() || updatedAt.isBefore(submittedAt)) {
                throw new IllegalArgumentException("Invalid acknowledged request");
            }
            Objects.requireNonNull(context);
            Objects.requireNonNull(type);
            Objects.requireNonNull(message);
            Objects.requireNonNull(status);
        }
    }

    record Update(Connection connection, List<Request> requests) {
        public Update { requests = List.copyOf(requests); }
    }

    @FunctionalInterface interface Watch extends AutoCloseable {
        @Override void close();
    }

    /** Use only after the server establishes that the request was never accepted. */
    final class Rejected extends RuntimeException {}

    boolean available();
    CompletionStage<Request> send(Command command);
    Watch watch(Context context, Consumer<Update> listener);

    static AssistanceService unavailable() {
        return new AssistanceService() {
            @Override public boolean available() { return false; }
            @Override public CompletionStage<Request> send(Command command) {
                return CompletableFuture.failedFuture(new Rejected());
            }
            @Override public Watch watch(Context context, Consumer<Update> listener) {
                listener.accept(new Update(Connection.DISCONNECTED, List.of()));
                return () -> {};
            }
        };
    }
}
