package com.roseroots.customer.services.api;

import com.roseroots.customer.services.AssistanceService;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Wire mapping is supplied by the shared authenticated client once staff and Customer agree on it. */
public final class SpringBootAssistanceService implements AssistanceService {
    public interface AuthenticatedClient {
        boolean compatible();
        CompletionStage<Request> submit(Command command);
        Watch subscribeAndReload(Context context, Consumer<Update> listener);
    }

    private final AuthenticatedClient client;

    public SpringBootAssistanceService(AuthenticatedClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override public boolean available() { return client.compatible(); }
    @Override public CompletionStage<Request> send(Command command) {
        return available() ? client.submit(command) : AssistanceService.unavailable().send(command);
    }
    @Override public Watch watch(Context context, Consumer<Update> listener) {
        return available() ? client.subscribeAndReload(context, listener) : AssistanceService.unavailable().watch(context, listener);
    }
}
