# Admin Phase 3B Reviews & Insights

## Audit and ownership

Customer at `e5f58fa` has no implemented review submission flow, review
DTO or review transport. Its ordering draft focuses on orders and bills.
Project Plan 1.1 proposes submitting feedback after service or checkout
through `POST /api/v1/reviews`, linked to the authorized dining session.

Kitchen at `502f704` has an expanded local application with a read-only
review monitor. `CustomerReview` holds local numeric review and session
IDs, an integer 1–5 rating, an offset timestamp, topics, and a comment.
`LocalKitchenReviewService` filters local records to food-related topics.
Its proposed shared `GET /api/v1/kitchen/reviews` uses canonical UUIDs,
newest-first pagination, a session ID, UTC timestamp, and an optional
comment. Kitchen food topics are FOOD_QUALITY, TASTE, TEMPERATURE,
ORDER_ACCURACY and PREPARATION_SPEED. The local model also defines
CLEANLINESS, STAFF_HELPFULNESS and AMBIENCE for wider feedback.
Preparation speed is the existing speed category.

The audit found no shared review persistence, live Customer submission,
review WebSocket channel, or approved Manager follow-up implementation.
The plan proposes `PATCH /api/v1/admin/reviews/{id}`. Member 2 owns review
persistence, scoped food feeds, approved follow-up transitions and any
event delivery. Member 1 connects the Customer submission workflow.
This phase adds only the Manager view and read contract. Follow-up status
and internal responses can be displayed when supplied. Writes and sharing
actions remain unavailable.

## Proposed Manager projection

`GET /api/v1/admin/reviews` requires the existing Manager session and sends
`Cache-Control: no-store`. Its optional parameters are `q` (maximum 120
characters), inclusive business dates `from` and `to` (ISO local dates),
integer `rating` (1–5), `topic`, `followUp`, `cursor` and `limit`
(default 25, range 1–100). The owner should search permitted comments,
review ID, and service reference, and return newest first with a stable
ID tie-break and query-bound cursors.

Register one authoritative [ReviewSource](../server/src/main/java/com/roseroots/server/admin/ReviewSource.java)
provider. Its response contains `insights`, `items` and `nextCursor`.
Each review has an ID, session ID, optional table or pickup reference,
UTC submission instant, optional rating, topics, optional comment,
optional follow-up state and optional internal response. The draft status
codes are NEEDS_ATTENTION, FOLLOWED_UP and RESOLVED. Owner-supplied
internal responses stay on the Manager projection. Details use the
selected review projection. Missing provider responses return HTTP 503
with `REVIEW_SOURCE_UNAVAILABLE`. Empty queries return an empty list.
Review or note payloads should stay out of general logs and event messages.

## Analytics semantics

The owner supplies analytics for the full filtered result, independent of
cursor and page size. [ReviewInsights](../server/src/main/java/com/roseroots/server/admin/ReviewInsights.java)
implements the same calculations when supplied the complete set.

- Submitted total includes all matching reviews. Star distribution and
  mean exclude missing ratings. Rounding is half-up to two decimals.
- Positive is 4–5 stars, neutral is 3, and negative is 1–2. A missing
  rating is excluded from all three groups. An empty rated set has no mean.
- Category counts include all reviews tagged with that category once per
  review. Multi-tag reviews contribute to each applicable category, so
  category counts can sum above the submitted total. Category averages use
  rated reviews within that category only.
- Daily trends bucket dated reviews in the provider's business timezone.
  Missing timestamps are excluded from the trend. Daily averages use known
  ratings. The screen displays submission times in the desktop timezone.
- Follow-up counts use the owner's current states when that field is
  supported. Null insight fields show unavailable. The screen never
  infers follow-up state from the star rating.

The screen uses the existing configurable HTTPS origin. Refresh is manual
until Member 2 supplies authorized invalidations through the shared
WebSocket transport. If integrated later, events should prompt a REST
refresh without including feedback text. No migrations or new storage
are required.

## Verification

All 60 tests passed, and both Windows launcher distributions built.
The running JavaFX app was checked over trusted local HTTPS with the
packaged server and a separate test-only provider. The fixture verified
analytics, search, detail, follow-up display, an empty result, and a
narrow window. No review fixtures were written to PostgreSQL.

- [Normal unavailable view](phase3b-screenshots/reviews-unavailable.png)
- [Analytics fixture](phase3b-screenshots/reviews-insights-fixture.png)
- [Recent reviews fixture](phase3b-screenshots/reviews-table-fixture.png)
- [Review detail fixture](phase3b-screenshots/review-details-fixture.png)
