package com.roseroots.customer.services;

import static org.junit.jupiter.api.Assertions.*;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalDevice;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.AssistanceService.*;
import com.roseroots.customer.services.api.SpringBootAssistanceService;
import com.roseroots.customer.state.AssistanceState;
import com.roseroots.customer.state.AssistanceState.Outcome;
import com.roseroots.customer.state.CustomerState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AssistanceWorkflowTest {
    private final Instant now = Instant.parse("2026-11-21T18:00:00Z");
    private final TerminalSession session = session(false, TerminalSession.Status.OPEN);
    private final Context visit = Context.from(session);
    private final FakeClient client = new FakeClient();
    private final AssistanceState state = new AssistanceState(new SpringBootAssistanceService(client),
            Clock.fixed(now, ZoneOffset.UTC), Runnable::run);

    @Test void selectionsAndOptionalOtherMessageAreValidated() {
        assertEquals(7, Type.values().length);
        assertEquals("", new Command(visit, UUID.randomUUID(), Type.OTHER, "", now).message());
        assertEquals("High chair", new Command(visit, UUID.randomUUID(), Type.OTHER, " High chair ", now).message());
        assertThrows(IllegalArgumentException.class, () -> new Command(visit, UUID.randomUUID(), Type.DRINKING_WATER, "note", now));
        assertThrows(IllegalArgumentException.class, () -> new Command(visit, UUID.randomUUID(), Type.OTHER, "x".repeat(241), now));
    }

    @Test void associatesAuthoritativeSessionAndKeepsCartAndVisitOnReopen() {
        var customer = new CustomerState(state);
        customer.activateSession(session, false);
        var item = new com.roseroots.customer.services.mock.MockMenuService(true)
                .loadCatalog().toCompletableFuture().join().item("doro-wat");
        assertTrue(customer.addToDraft(item));
        var scoped = customer.assistance().visit(Context.from(customer.terminalSession()));
        var observation = scoped.observe(() -> {});
        scoped.send(Type.DRINKING_WATER, "");
        var submitted = client.commands.getFirst();
        assertEquals(session.sessionId(), submitted.context().session().toString());
        assertEquals("terminal", submitted.context().device());
        assertEquals("07", submitted.context().destination());
        assertEquals(now, submitted.requestedAt());
        observation.close();
        client.response.complete(request(submitted, Status.SUBMITTED));
        assertSame(scoped, customer.assistance().visit(Context.from(customer.terminalSession())));
        assertSame(session, customer.terminalSession());
        assertEquals(1, customer.cart().count());
        assertEquals(0, state.visit(Context.from(session(false, TerminalSession.Status.OPEN))).requests().size());
        assertEquals(Outcome.SENT, scoped.outcome());
    }

    @Test void timesOutWithoutConfirmationAndReusesExactRequestWhilePreventingDuplicates() {
        var scoped = state.visit(visit);
        scoped.observe(() -> {});
        scoped.send(Type.OTHER, "High chair");
        scoped.send(Type.CUTLERY, "");
        assertEquals(Outcome.SENDING, scoped.outcome());
        assertEquals(1, client.commands.size());
        assertTrue(scoped.requests().isEmpty());
        client.response.completeExceptionally(new java.net.http.HttpTimeoutException("test timeout"));
        assertEquals(Outcome.UNCERTAIN, scoped.outcome());
        assertTrue(scoped.requests().isEmpty());
        client.update.accept(new Update(Connection.DISCONNECTED, List.of()));
        assertFalse(scoped.canRetry());
        scoped.retry();
        assertEquals(1, client.commands.size());
        client.update.accept(new Update(Connection.LIVE, List.of()));
        client.response = new CompletableFuture<>();
        scoped.retry();
        assertEquals(client.commands.getFirst(), client.commands.getLast());
        var confirmed = request(client.commands.getFirst(), Status.SUBMITTED);
        client.response.complete(confirmed);
        assertEquals(List.of(confirmed), scoped.requests());
        assertFalse(scoped.canSend(Type.OTHER));
        client.update.accept(new Update(Connection.LIVE, List.of(request(client.commands.getFirst(), Status.COMPLETED))));
        assertTrue(scoped.canSend(Type.OTHER));
        client.response = new CompletableFuture<>();
        scoped.send(Type.OTHER, "");
        assertNotEquals(client.commands.getFirst().key(), client.commands.getLast().key());
    }

    @Test void ignoresForeignAndOlderUpdatesAndKeepsLastConfirmedStatusDuringReconnect() {
        var scoped = state.visit(visit);
        var watch = scoped.observe(() -> {});
        scoped.send(Type.WAITER, "");
        var sent = client.commands.getFirst();
        client.response.complete(request(sent, Status.ACKNOWLEDGED));
        client.update.accept(new Update(Connection.RECONNECTING, List.of(request(sent, Status.COMPLETED))));
        assertEquals(Status.ACKNOWLEDGED, scoped.requests().getFirst().status());
        var obsolete = client.update;
        watch.close();
        scoped.observe(() -> {});
        obsolete.accept(new Update(Connection.LIVE, List.of(request(sent, Status.COMPLETED))));
        assertEquals(Status.ACKNOWLEDGED, scoped.requests().getFirst().status());
        var foreign = new Command(Context.from(session(false, TerminalSession.Status.OPEN)), UUID.randomUUID(), Type.NAPKINS, "", now);
        client.update.accept(new Update(Connection.LIVE, List.of(request(foreign, Status.COMPLETED), request(sent, Status.SUBMITTED))));
        assertEquals(1, scoped.requests().size());
        assertEquals(Status.ACKNOWLEDGED, scoped.requests().getFirst().status());
        client.update.accept(new Update(Connection.LIVE, List.of(request(sent, Status.IN_PROGRESS))));
        assertEquals(Status.IN_PROGRESS, scoped.requests().getFirst().status());
    }

    @Test void disconnectedDemoAndDraftCannotSend() {
        var offline = new CustomerState().assistance().visit(visit);
        offline.observe(() -> {});
        offline.send(Type.BILL, "");
        assertEquals(Connection.DISCONNECTED, offline.connection());
        assertEquals(Outcome.IDLE, offline.outcome());
        assertNull(Context.from(session(true, TerminalSession.Status.OPEN)));
        assertNull(Context.from(session(false, TerminalSession.Status.LOCAL_DRAFT)));
    }

    @Test void rejectionDoesNotConfirmAndMismatchedAcknowledgementStaysUncertain() {
        var scoped = state.visit(visit);
        scoped.observe(() -> {});
        scoped.send(Type.BILL, "");
        client.response.completeExceptionally(new AssistanceService.Rejected());
        assertEquals(Outcome.REJECTED, scoped.outcome());
        assertTrue(scoped.requests().isEmpty());
        client.response = new CompletableFuture<>();
        scoped.send(Type.CUTLERY, "");
        client.response.complete(request(client.commands.getFirst(), Status.SUBMITTED));
        assertEquals(Outcome.UNCERTAIN, scoped.outcome());
        assertTrue(scoped.requests().isEmpty());
    }

    private Request request(Command command, Status status) {
        return new Request("test-" + command.key(), command.context(), command.type(), command.message(), now,
                now.plusSeconds(status.ordinal()), status);
    }

    private static TerminalSession session(boolean demo, TerminalSession.Status status) {
        return new TerminalSession(status == TerminalSession.Status.OPEN ? UUID.randomUUID().toString() : null,
                new TerminalDevice("terminal", new TerminalDevice.TableAssignment("fixture-table", "07"), demo),
                ServiceMode.DINE_IN, status, null, List.of(),
                status == TerminalSession.Status.LOCAL_DRAFT ? "local" : null);
    }

    private static final class FakeClient implements SpringBootAssistanceService.AuthenticatedClient {
        final List<Command> commands = new ArrayList<>();
        CompletableFuture<Request> response = new CompletableFuture<>();
        Consumer<Update> update;

        @Override public boolean compatible() { return true; }

        @Override public CompletableFuture<Request> submit(Command command) {
            commands.add(command);
            return response;
        }

        @Override public Watch subscribeAndReload(Context context, Consumer<Update> listener) {
            update = listener;
            listener.accept(new Update(Connection.LIVE, List.of()));
            return () -> {};
        }
    }
}
