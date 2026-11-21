package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class OrderingProblem extends RuntimeException {
    final int status;
    final String code;
    private final ArrayNode violations;

    OrderingProblem(int status, String code, String detail) {
        this(status, code, detail, null);
    }

    OrderingProblem(int status, String code, String detail, ArrayNode violations) {
        super(detail);
        this.status = status;
        this.code = code;
        this.violations = violations;
    }

    static OrderingProblem bad(String detail) { return new OrderingProblem(400, "BAD_REQUEST", detail); }
    static OrderingProblem missing() { return new OrderingProblem(404, "NOT_FOUND", "Resource unavailable."); }

    static OrderingProblem conflict(String code, String detail) {
        return new OrderingProblem(409, code, detail);
    }

    ObjectNode body() {
        ObjectNode problem = OrderingJson.object().put("type", "about:blank").put("title", HttpStatus.valueOf(status).getReasonPhrase())
                .put("status", status).put("code", code).put("detail", getMessage())
                .put("traceId", UUID.randomUUID().toString());
        if (violations != null) {
            problem.set("violations", violations);
        }
        return problem;
    }
}
