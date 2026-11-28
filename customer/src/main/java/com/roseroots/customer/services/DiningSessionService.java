package com.roseroots.customer.services;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.TerminalSession;
import java.util.concurrent.CompletionStage;

/** Resolves the provisioned terminal and its active session without accepting client-supplied table or session IDs. */
public interface DiningSessionService {
    CompletionStage<TerminalSession> openOrResume(ServiceMode mode);
}
