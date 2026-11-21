# Shared Customer Reviews and Handoff contract

One optional visit review is available after food from that session has been explicitly served or collected. [OpenAPI draft 3](ordering-api-v1.openapi.json) contains the wire shapes for Customer, Kitchen, and Managers.

## Eligibility and submission

An authenticated Kitchen staff bearer records actual handoff via `POST /api/v1/kitchen/orders/{id}/handoff`. Send the order's current `expectedVersion` with `SERVED` for Dine-In or `COLLECTED` for Takeaway. The server checks that the order is READY in the staff member's restaurant and that each non-cancelled line is ready and cleared. It records the actor and time along with the order, item, and outbox versions. READY alone has no review eligibility.

The owning terminal reads `GET /api/v1/sessions/{id}/review` for `eligible` and the existing review, if any. The terminal may submit `POST /api/v1/sessions/{id}/reviews` after at least one item has been served or collected. A session's expiry prevents further orders and leaves the existing review entitlement available to its authenticated owner. Skipping the optional form creates no request, review, or rating.

| Field | Proposed contract |
|---|---|
| `rating` | Integer 1–5 or null. Unrated reviews contribute to review counts and stay out of rating denominators. |
| `topics` | Zero to eight distinct values from the existing Admin topic enum. |
| `comment` | Optional plain text, trimmed and limited to 500 characters. |
| `Idempotency-Key` | Required UUID scoped to the authenticated terminal and session. |

Send at least one rating, topic, or nonblank comment. `201` returns the accepted review. An identical replay returns the original acceptance with `200`. Changed input under the same key returns `409 IDEMPOTENCY_CONFLICT`. A second independently keyed submission returns `409 REVIEW_ALREADY_EXISTS`. An owned session without actual handoff receives `422 REVIEW_NOT_ELIGIBLE`.

## Storage and staff access

Persist the review UUID, session foreign key, submitted time, nullable rating, topics, original comment, submission key, and canonical request digest. A unique session constraint and transactional idempotency prevent duplicate reviews. Treat all comments as plain text. Rating and sentiment do not filter a review out of its authorized projection.

- **Kitchen** uses restaurant-scoped `GET /api/v1/kitchen/reviews` with an opaque cursor and limit. Return reviews tagged with `FOOD_QUALITY`, `TASTE`, `TEMPERATURE`, `ORDER_ACCURACY`, or `PREPARATION_SPEED`. Project the matching topics, rating, submission time, and original feedback text for those reviews. The Customer form should explain that tagging a food or preparation topic shares the feedback text with Kitchen. Session and table identifiers and Manager follow-up stay private to their other projections.
- **Managers** retain the existing cookie-protected `GET /api/v1/admin/reviews` filters and insights. `PATCH /api/v1/admin/reviews/{id}/follow-up` requires a Manager cookie and CSRF token. Send `followUp`, `internalResponse`, and the current `expectedVersion`. Persist the Manager principal, status, internal note, timestamp, and version in the private follow-up audit. A stale edit returns `409 VERSION_CONFLICT`.
- **Customer** sees the status of its own submission. Staff credentials cannot submit on a customer's behalf. Connected views use persisted records and show empty or unavailable states as appropriate.

## Acceptance

Verify explicit Dine-In and Takeaway handoff, READY rejection, PostgreSQL persistence, concurrent same-key replay, session ownership, duplicate rejection, and Kitchen and Manager access boundaries. Verify that unrated reviews do not affect averages, a skipped form leaves review counts unchanged, and relevant low ratings remain visible. Verify Manager edits are versioned and private.
