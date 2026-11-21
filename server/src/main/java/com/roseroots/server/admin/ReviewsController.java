package com.roseroots.server.admin;

import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.roseroots.server.admin.ReviewSource.*;

@RestController
class ReviewsController {
    private final ObjectProvider<ReviewSource> source;

    ReviewsController(ObjectProvider<ReviewSource> source) {
        this.source = source;
    }

    @GetMapping(AdminSecurity.PREFIX + "/reviews")
    ResponseEntity<?> reviews(@RequestParam(name = "q", defaultValue = "") String search,
                              @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(name = "rating", required = false) Integer rating,
                              @RequestParam(name = "topic", required = false) Topic topic,
                              @RequestParam(name = "followUp", required = false) FollowUp followUp,
                              @RequestParam(name = "cursor", required = false) String cursor,
                              @RequestParam(name = "limit", defaultValue = "25") int limit) {
        if (search.strip().length() > 120 || from != null && to != null && from.isAfter(to)
                || rating != null && (rating < 1 || rating > 5) || limit < 1 || limit > 100
                || cursor != null && cursor.length() > 512) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_FILTER", "Check the review filters.");
        }
        var provider = source.getIfAvailable();
        if (provider == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "REVIEW_SOURCE_UNAVAILABLE",
                    "The shared customer review service is not connected.");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                    provider.reviews(new Query(search.strip(), from, to, rating, topic, followUp, cursor, limit)));
        } catch (IllegalArgumentException e) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_FILTER", "Check the review filters or reload from the first page.");
        }
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(detail);
    }
}
