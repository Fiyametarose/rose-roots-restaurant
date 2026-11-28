package com.roseroots.customer.services.api;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.DiningSessionService;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** The future transport authenticates the installation. The server resolves its device, table and session. */
public final class SpringBootDiningSessionService implements DiningSessionService {
    private final AuthenticatedTerminalClient client;

    public SpringBootDiningSessionService(AuthenticatedTerminalClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public CompletionStage<TerminalSession> openOrResume(ServiceMode mode) {
        return client.openOrResume(mode);
    }

    public interface AuthenticatedTerminalClient {
        CompletionStage<TerminalSession> openOrResume(ServiceMode mode);
    }
}
