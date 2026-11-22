package com.roseroots.server.admin;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.roseroots.server.admin.OperationsSource.*;

@RestController
class OperationsController {
    private final ObjectProvider<OperationsSource> source;

    OperationsController(ObjectProvider<OperationsSource> source) {
        this.source = source;
    }

    @GetMapping(AdminSecurity.PREFIX + "/orders")
    ResponseEntity<Page> orders(@RequestParam(name = "q", defaultValue = "") String search,
                                @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(name = "orderStatus", required = false) OrderStatus orderStatus,
                                @RequestParam(name = "paymentStatus", required = false) PaymentStatus paymentStatus,
                                @RequestParam(name = "serviceType", required = false) ServiceType serviceType,
                                @RequestParam(name = "paymentMethod", required = false) String paymentMethod,
                                @RequestParam(name = "sort", defaultValue = "NEWEST") Sort sort,
                                @RequestParam(name = "cursor", required = false) String cursor,
                                @RequestParam(name = "limit", defaultValue = "25") int limit) {
        if (search.strip().length() > 120 || limit < 1 || limit > 100 || cursor != null && cursor.length() > 512
                || paymentMethod != null && !paymentMethod.matches("[A-Z0-9_]{1,32}")
                || from != null && to != null && from.isAfter(to)) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_ORDERS_FILTER", "Check the filters and date range.");
        }

        var query = new Query(search.strip().toLowerCase(Locale.ROOT), from, to, orderStatus,
                paymentStatus, serviceType, paymentMethod, sort, cursor, limit);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations().orders(query));
        } catch (IllegalArgumentException invalidCursor) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_ORDERS_FILTER", "Check the filters or return to the first page.");
        }
    }

    @GetMapping(AdminSecurity.PREFIX + "/orders/{id}")
    ResponseEntity<Detail> order(@PathVariable("id") UUID id) {
        var detail = operations().order(id).orElseThrow(() ->
                problem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "This order is no longer available."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail);
    }

    private OperationsSource operations() {
        var provider = source.getIfAvailable();
        if (provider == null) {
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "OPERATIONS_SOURCE_UNAVAILABLE",
                    "The shared order and payment services are not connected.");
        }
        return provider;
    }

    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ProblemDetail> operationProblem(ErrorResponseException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(failure.getBody());
    }

    private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return new ErrorResponseException(status, body, null);
    }
}
