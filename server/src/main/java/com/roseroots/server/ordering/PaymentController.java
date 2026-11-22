package com.roseroots.server.ordering;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.roseroots.server.ordering.OrderingController.body;
import static com.roseroots.server.ordering.OrderingController.response;
import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@RestController
@RequestMapping("/api/v1")
@Profile("catalog")
class PaymentController {
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private final Payments payments;

    PaymentController(Payments payments) { this.payments = payments; }

    @GetMapping("/sessions/{id}/payable-balance")
    ResponseEntity<String> balance(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session) {
        return response(200, payments.balance(actor, id(session)));
    }

    @PostMapping(path = "/sessions/{id}/payments", consumes = JSON)
    ResponseEntity<String> start(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
                                 @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var input = fields(body(request), "method", "expectedBillVersion");
        return response(payments.start(actor, id(session), id(key), choice(input, "method", "CASH", "MPESA", "CARD"),
                integer(input, "expectedBillVersion", 1, MAX_SAFE)));
    }

    @GetMapping("/sessions/{id}/payments/{paymentId}")
    ResponseEntity<String> read(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
                                @PathVariable("paymentId") String payment) {
        return response(200, payments.read(actor, id(session), id(payment)));
    }

    @PostMapping(path = "/sessions/{id}/payments/{paymentId}/cancel", consumes = JSON)
    ResponseEntity<String> cancel(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
                                  @PathVariable("paymentId") String payment, HttpServletRequest request) {
        var input = fields(body(request), "expectedVersion");
        return response(200, payments.cancel(actor, id(session), id(payment), integer(input, "expectedVersion", 1, MAX_SAFE)));
    }

    @GetMapping("/sessions/{id}/receipts/{receiptId}")
    ResponseEntity<String> receipt(@AuthenticationPrincipal Actor actor, @PathVariable("id") String session,
                                   @PathVariable("receiptId") String receipt) {
        return response(200, payments.receipt(actor, id(session), id(receipt)));
    }

    @GetMapping("/admin/payments")
    ResponseEntity<String> staffQueue() { return response(200, payments.staffQueue()); }

    @PostMapping(path = "/staff/payments/{paymentId}/confirm-cash", consumes = JSON)
    ResponseEntity<String> confirm(@PathVariable("paymentId") String payment, Principal manager, HttpServletRequest request) {
        var input = fields(body(request), "expectedVersion", "expectedBillVersion");
        return response(200, payments.confirmCash(id(payment), integer(input, "expectedVersion", 1, MAX_SAFE),
                integer(input, "expectedBillVersion", 1, MAX_SAFE), manager.getName()));
    }

    @PostMapping(path = "/admin/payments/{paymentId}/resolve-cash", consumes = JSON)
    ResponseEntity<String> resolve(@PathVariable("paymentId") String payment, Principal manager, HttpServletRequest request) {
        var input = fields(body(request), "expectedVersion", "status", "reason");
        String reason = text(input, "reason", 240).strip();
        if (reason.isEmpty()) throw OrderingProblem.bad("A resolution reason is required.");
        return response(200, payments.resolveCash(id(payment), integer(input, "expectedVersion", 1, MAX_SAFE),
                choice(input, "status", "FAILED", "CANCELLED"), reason, manager.getName()));
    }

    @PostMapping(path = "/admin/sessions/{id}/close", consumes = JSON)
    ResponseEntity<String> close(@PathVariable("id") String session, Principal manager, HttpServletRequest request) {
        var input = fields(body(request), "expectedBillVersion");
        return response(200, payments.close(id(session), integer(input, "expectedBillVersion", 1, MAX_SAFE), manager.getName()));
    }

    @ExceptionHandler(OrderingProblem.class)
    ResponseEntity<String> problem(OrderingProblem error) {
        return ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(error.body().toString());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<String> storage() {
        return problem(new OrderingProblem(503, "SERVICE_UNAVAILABLE", "Payment storage is unavailable."));
    }
}
