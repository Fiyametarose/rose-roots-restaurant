# Shared Customer Reviews contract proposal

**Status** Awaiting Customer, Kitchen, and Admin approval. The first slice proposes one authentic visit review per eligible dining session. Server-issued UUIDs link the review to the session internally. The current Customer checkout has no review submission interface. Kitchen's existing local model uses numeric fixture IDs and requires a rating. Admin's existing read projection supports UUIDs and unrated submissions. [Current Admin review behavior](admin-phase-3b.md)

## Eligibility and submission

Propose `POST /api/v1/sessions/{id}/reviews` for the terminal bearer that owns a session with at least one accepted, non-cancelled order. The server evaluates eligibility and assigns the UUID and timestamps. Confirm whether to allow reviews while the session is open and set a review window for closed or expired sessions. For the first slice, a review can be ratings-only or written, with at least one rating, topic, or nonblank comment.

| Field | Proposed contract |
|---|---|
| `rating` | Integer 1–5 or null. Unrated reviews contribute to review counts and stay out of rating denominators. |
| `topics` | Zero to eight distinct values from the existing Admin topic enum. |
| `comment` | Optional plain text, trimmed and limited to 500 characters. |
| `Idempotency-Key` | Required UUID scoped to the authenticated terminal and session. |

`201` returns the accepted review. An identical replay returns the original acceptance with `200`. Changed input under the same key returns `409 IDEMPOTENCY_CONFLICT`. A second independently keyed submission for the same session returns `409 REVIEW_ALREADY_EXISTS`. Use `422 REVIEW_NOT_ELIGIBLE` when the owned session fails eligibility, and retain the existing Problem envelope, authentication errors, and scoped `404` behavior. Propose `GET /api/v1/sessions/{id}/review` for owner-scoped readback after reconnect.

## Storage and staff access

Persist the review UUID, session foreign key, submitted time, nullable rating, topics, original comment, submission key, canonical request digest, and moderation visibility. A unique session constraint and transactional idempotency prevent duplicate reviews. Keep Manager follow-up and internal notes in separate records with actor, version, and timestamps when those mutations are approved.

- **Kitchen** uses restaurant-scoped `GET /api/v1/kitchen/reviews` with an opaque cursor and limit. Return only published food-related topics, rating, submission time, and comment cleared for Kitchen. The eligible topics are `FOOD_QUALITY`, `TASTE`, `TEMPERATURE`, `ORDER_ACCURACY`, and `PREPARATION_SPEED`. Free-text comments and session or table identifiers require an explicit privacy decision before adding them to the Kitchen projection.
- **Managers** retain the existing cookie-protected `GET /api/v1/admin/reviews` filters and insights. Populate its existing review source from PostgreSQL. Keep internal notes and follow-up details within the Manager projection.
- **Customer** sees the status of its own submission. Staff credentials cannot submit on a customer's behalf. Connected views use persisted records and show empty or unavailable states as appropriate.

## Acceptance

Verify PostgreSQL persistence, concurrent same-key replay, session ownership, rejection of duplicate submissions, and Kitchen and Manager access boundaries. Verify that unrated reviews do not affect averages and that suppressed comments and private fields stay out of the Kitchen projection. Confirm eligibility, nullable rating, Kitchen comment visibility, and moderation defaults before adding the schema to a shared OpenAPI revision.
