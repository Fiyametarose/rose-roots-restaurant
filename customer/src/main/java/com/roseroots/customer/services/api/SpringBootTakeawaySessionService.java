package com.roseroots.customer.services.api;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalSession;
import com.roseroots.customer.services.TakeawaySessionService;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class SpringBootTakeawaySessionService implements TakeawaySessionService {
    private final SpringBootDiningSessionService.AuthenticatedTerminalClient client;

    public SpringBootTakeawaySessionService(SpringBootDiningSessionService.AuthenticatedTerminalClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public CompletionStage<TerminalSession> openOrResume() {
        return client.openOrResume(ServiceMode.TAKEAWAY).thenApply(session -> {
            if (session == null || session.mode() != ServiceMode.TAKEAWAY
                    || !session.isOpen() || session.device().demo()) {
                throw new IllegalStateException("No authoritative takeaway session was returned");
            }
            return session;
        });
    }
}
