package com.roseroots.customer.services;

import com.roseroots.customer.models.Payment;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PaymentService {
    CompletionStage<Payment.Balance> balance(UUID session);
    CompletionStage<Payment.Attempt> start(UUID session, long billVersion, UUID key);
    CompletionStage<Payment.Attempt> attempt(UUID session, UUID id);
    CompletionStage<Payment.Attempt> cancel(UUID session, UUID id, long version);
    CompletionStage<Payment.Receipt> receipt(UUID session, UUID id);
}
