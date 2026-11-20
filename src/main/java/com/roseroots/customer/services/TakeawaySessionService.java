package com.roseroots.customer.services;

import com.roseroots.customer.models.TerminalSession;
import java.util.concurrent.CompletionStage;

public interface TakeawaySessionService {
    CompletionStage<TerminalSession> openOrResume();
}
