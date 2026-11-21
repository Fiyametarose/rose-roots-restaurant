# Phase 4B Kitchen contract proposal

**Status** Kitchen-owned proposal for Customer and Admin review. The checked-in Customer OpenAPI is `1.0.0-draft.1`, a review baseline. All additions, role decisions and behavioral refinements below require approval. This document does not publish a new shared schema or authorize implementation.

## Existing shared baseline

The target is Java 21 with two JavaFX applications, one Spring Boot authority and one PostgreSQL database. Customer's [ordering contract](../../../rose-roots-customer/docs/ordering-api-v1.openapi.json) and [integration guide](../../../rose-roots-customer/docs/ordering-integration-v1.md) define the draft baseline. Preserve the existing `Order`, `OrderLine`, pricing, allergen, submission, idempotency and Problem schemas. Existing operations include:

| Workflow | Draft operations under `/api/v1` |
| --- | --- |
| Customer ordering and tracking | `POST /order-quotes`, `POST /orders`, `GET /orders/{id}`, `GET /sessions/{id}/orders` |
| Kitchen | `GET /kitchen/orders`, `PATCH /kitchen/items/{id}/status`, `POST /kitchen/items/{id}/allergy-review`, `POST /kitchen/orders/{id}/rush` |
| Allergy response | `POST /orders/{id}/items/{itemId}/review-response`. Its current `CustomerReview` schema represents an allergy response. |
| Capacity and estimates | `GET /admin/stations`, `PATCH /admin/stations/{id}`, `GET /admin/prep-times`, `PATCH /admin/prep-times/{id}`, `POST /wait-estimates` |

Customer proposes bearer-protected HTTPS and STOMP 1.2 over WSS. Admin's current Spring Security chain protects its Manager workspace using a session cookie, form login and CSRF. Admin has a LAN TLS profile and documents `ROSE_BACKEND_URL` as the shared origin setting. See [Admin deployment](../../../rose-roots-admin/docs/two-pc-demonstration.md).

## Proposed common rules

- **ID** means a canonical lowercase UUID v4 string. Align schema validation with prose, including version and variant. The server issues persistent IDs. **Version** is an integer from 1 through 9007199254740991. **UTC** means an ISO 8601 timestamp ending in `Z`. Strings use the stated bounds after trimming. Fields are required unless explicitly optional. Nullable fields are present with JSON `null`. Reject unknown mutation fields. JSON field names and enums below are proposals.
- Scope every request and snapshot cursor to the authenticated restaurant, principal and applicable permission. Use the draft Problem response for malformed requests (400), missing/expired credentials (401), denied roles (403), missing/out-of-scope resources (404), version or state conflicts (409), invalid semantics (422), throttling (429), and temporary outages (503). Reuse `VERSION_CONFLICT`, `INVALID_TRANSITION` and `REVIEW_REQUIRED` as applicable. Never include private notes or secrets in a Problem.
- Increment the changed resource version in the same transaction as its update. Any committed change visible on a ticket increments the order version. A Kitchen item update compares the item's version; Rush and service confirmation compare the order version. Use returned snapshots or an authenticated read after conflicts or ambiguous responses. A second request on a stale expected version must never repeat side effects.

## Proposed Kitchen ticket and read operations

Extend `KitchenTicket` with the fields below. Keep the existing nested `order` and its full `lines` unchanged. Every successful Kitchen mutation returns the resulting `KitchenTicket` with HTTP 200.

| JSON field | Type and validation |
| --- | --- |
| `order` | Existing `Order`. Its `id`, `sessionId`, `mode`, derived `status`, `acceptedAt`, and `version` are authoritative. Its lines supply IDs, quantities, selected options, instructions, preparation and allergy-review state, visit and item declarations, and computed ingredient/cross-contact risk. |
| `orderReference` | String 1–60. Server-issued display reference, stable within a restaurant and distinct from the UUID. |
| `serviceDestination` | Existing string 1–60, display-ready table or pickup reference. |
| `priority`, `delayed` | Existing enum `NORMAL` or `RUSH` and independent boolean. |
| `delayTargetSeconds` | Integer 1–86400, policy saved at acceptance. Proposed delay starts at `acceptedAt` and applies to unfinished orders at or beyond the target. READY and terminal orders have `delayed=false`. |
| `preparationStartedAt`, `readyAt`, `finalizedAt` | Nullable UTC. First item start, time all required lines became ready, explicit terminal transition time. READY has `finalizedAt=null`. |
| `itemOperations` | Array 1–100 with exactly one entry per `order.lines[].id`. Entry keys are `itemId` ID, `stationId` ID, `stationName` string 1–240, `preparationStartedAt` nullable UTC, `readyAt` nullable UTC. Station assignment and name are snapshotted for the accepted line. |
| `lastRushChange` | Null or object with `action` enum `APPLY_RUSH` or `REMOVE_RUSH`, `reason` string 1–240 containing a letter/digit, `actedAt` UTC, `staffId` ID. Persist all entries server-side; this projection returns the latest, including removal. |

- `GET /kitchen/orders?limit=50&cursor=…` lists outstanding RECEIVED, PREPARING and READY, including allergy holds, across the authenticated restaurant. `GET /kitchen/orders/{id}` is an added authorized read for one active or terminal ticket, useful for conflict recovery and details. Use `KitchenPage` with existing `tickets` and nullable `nextCursor`, plus proposed required `snapshotAsOf` UTC. Limits 1–200, default 50. Cursor is opaque, max 500 characters, bound to principal, scope and filters. First page freezes the snapshot and delay evaluation time. All continuation pages use the same snapshot and stable ordering by `acceptedAt,id`. Propose a 2-minute lifetime and HTTP 409 with proposed code `SNAPSHOT_EXPIRED` if the cursor expires. Restart from page one on expiration. No partial collection replaces the last complete view.
- Use authoritative `delayed` on load and refresh when due; the client can show elapsed time separately. A change produced solely by the advancing clock need not increment the order version or publish an event. Snapshot refresh must accept the same order version with a newer delay evaluation time.
- Use UUIDs directly in connected ticket handles, selections, deduplication and writes. Keep local numeric IDs and formatting in the local provider. Map to view rows at the provider boundary and use `orderReference` for display. Avoid deriving a numeric value from a UUID. Retain the existing JavaFX layouts and mock mode. Coordinate any minimal view-model key and asynchronous service-boundary changes after approval.

Representative outstanding page with one unstarted line (all fields shown):

```json
{
  "snapshotAsOf": "2026-11-20T18:02:00Z",
  "nextCursor": null,
  "tickets": [{
    "order": {
      "id": "00000000-0000-4000-8000-000000000010",
      "sessionId": "00000000-0000-4000-8000-000000000020",
      "mode": "DINE_IN", "status": "RECEIVED", "acceptedAt": "2026-11-20T18:01:00Z", "version": 1,
      "visitAllergy": {"kind": "NONE", "allergens": [], "other": ""},
      "lines": [{
        "id": "00000000-0000-4000-8000-000000000011",
        "pricing": {
          "clientLineId": "00000000-0000-4000-8000-000000000012",
          "menuItemId": "00000000-0000-4000-8000-000000000013",
          "name": "Doro Wat", "quantity": 1, "baseUnitMinor": 125000, "extendedMinor": 125000,
          "options": [{"groupId": "00000000-0000-4000-8000-000000000014", "optionId": "00000000-0000-4000-8000-000000000015", "name": "Sauce on the side", "unitAdjustmentMinor": 0}]
        },
        "allergy": {"kind": "NONE", "allergens": [], "other": ""},
        "preparationInstructions": "Pack injera separately",
        "preparationStatus": "RECEIVED", "allergyReviewStatus": "NOT_REQUIRED",
        "allergyRisk": {"ingredientMatches": [], "crossContactMatches": [], "unresolved": false},
        "billable": true, "version": 1, "activeSubstitution": null
      }],
      "totals": {"currency": "KES", "subtotalMinor": 125000, "serviceMinor": 0, "taxMinor": 0, "totalMinor": 125000}
    },
    "orderReference": "#A1023", "serviceDestination": "Table 07",
    "priority": "NORMAL", "delayed": false, "delayTargetSeconds": 900,
    "preparationStartedAt": null, "readyAt": null, "finalizedAt": null, "lastRushChange": null,
    "itemOperations": [{"itemId": "00000000-0000-4000-8000-000000000011",
      "stationId": "00000000-0000-4000-8000-000000000016", "stationName": "Hot Kitchen",
      "preparationStartedAt": null, "readyAt": null}]
  }]
}
```

## Proposed preparation, allergy, Rush and terminal writes

| Operation | Permitted transition and principal |
| --- | --- |
| `PATCH /kitchen/items/{id}/status` | Keep `{status,expectedVersion}`. Narrow mutation `status` to `PREPARING` or `READY`. Staff with kitchen preparation permission may move `RECEIVED → PREPARING → READY` one step at a time. Before starting or completing, verify the safety gate is clear. The server sets item and order timestamps and derives order state. |
| `POST /kitchen/items/{id}/allergy-review` | Retain the existing `StaffReview` shape. Propose a dedicated allergy-review permission granted initially to Supervisor and Manager accounts with training, pending owner approval. Accept only an unresolved, unstarted line and a permitted decision for the current review state. Keep private reasons in the restricted audit. |
| `POST /kitchen/orders/{id}/rush` | Keep `{priority,reason,expectedVersion}` with priority `RUSH` to apply or `NORMAL` to remove. Require Supervisor or Manager with appropriate restaurant scope and a trimmed reason 1–240 containing a letter or digit. Applies to RECEIVED or PREPARING. Same priority or READY/terminal orders return an invalid-transition conflict. Preserve all preparation and allergy state and previous audit entries. |
| **New** `POST /kitchen/orders/{id}/handoff` | Body `{status,expectedVersion}`; status enum `SERVED` or `COLLECTED`. Staff require explicit handoff permission in addition to an accepted staff role. Apply `SERVED` only to READY DINE_IN, and `COLLECTED` only to READY TAKEAWAY. In one transaction transition all ready, non-cancelled lines, preserve cancelled lines, finalize the order and record actor and time. |
| **New** `GET /kitchen/orders/history` | Authorized read of terminal SERVED, COLLECTED or CANCELLED orders as `KitchenPage`. Optional query fields `status` (those three enum values), `mode` (existing service mode), `q` (trimmed string 1–120 searching order reference, destination or item name), `from` inclusive and `to` exclusive (UTC, require `from < to` if both supplied). Date range filters `finalizedAt`. Use the same cursor and limit rules, ordered by `finalizedAt` descending then ID ascending. A history screen can show READY separately from the outstanding feed. |

**Safety and derivation.** Accept `NOT_REQUIRED` and `APPROVED_FOR_PREPARATION` as the only clear gates. Pending, clarification, deferral and customer-approval holds block cooking; `DECLINED` cancels the line and makes it nonbillable. Proposed allowed staff decisions on `PENDING_ALLERGY_REVIEW`, `CLARIFICATION_REQUESTED` or `DEFERRED` are `APPROVE`, `REQUEST_CLARIFICATION`, `DEFER`, `DECLINE` and `PROPOSE_SUBSTITUTION`, subject to validation and meaningful change. On `CUSTOMER_APPROVAL_REQUIRED`, wait for a Customer response or allow a staff decline; no approval before the required response. A valid clarification or accepted substitution returns to review; repricing and risk checks remain authoritative. The server verifies that unresolved risk is addressed before approval. Do not infer allergy clearance from empty data. The existing `StaffReview` uses `decision`, `reason` and item `expectedVersion`; `replacementSelections` is required only for substitution and uses existing `Selection[]` with 0–30 entries. Rush cannot clear a gate. Derive CANCELLED when all lines are cancelled, RECEIVED when all remaining lines are unstarted, READY when all remaining lines are ready, and PREPARING for other nonterminal mixtures. Explicit handoff alone produces SERVED or COLLECTED. Serialize aggregate derivation against concurrent line writes.

Representative HTTP requests (versions are applicable to each illustrated request):

| Action | Request | JSON body |
| --- | --- | --- |
| Start | `PATCH /kitchen/items/00000000-0000-4000-8000-000000000011/status` | `{"status":"PREPARING","expectedVersion":1}` |
| Ready | same item status path | `{"status":"READY","expectedVersion":2}` |
| Allergy decision | `POST /kitchen/items/00000000-0000-4000-8000-000000000011/allergy-review` | `{"decision":"REQUEST_CLARIFICATION","reason":"Confirm whether sesame was disclosed","expectedVersion":1}` |
| Apply Rush | `POST /kitchen/orders/00000000-0000-4000-8000-000000000010/rush` | `{"priority":"RUSH","reason":"Approved replacement for missed order","expectedVersion":1}` |
| Remove Rush | same Rush path | `{"priority":"NORMAL","reason":"Replacement is no longer urgent","expectedVersion":2}` |
| Dine-in handoff | `POST /kitchen/orders/00000000-0000-4000-8000-000000000010/handoff` | `{"status":"SERVED","expectedVersion":3}` |
| Takeaway handoff | `POST /kitchen/orders/00000000-0000-4000-8000-000000000030/handoff` | `{"status":"COLLECTED","expectedVersion":3}` |

Example history read `GET /kitchen/orders/history?status=COLLECTED&mode=TAKEAWAY&q=P-023&limit=25`. A matching response has the `KitchenPage` envelope above, with a ticket whose nested order has `"mode":"TAKEAWAY","status":"COLLECTED"`, `"serviceDestination":"Pickup P-023"`, and, for example, `"readyAt":"2026-11-20T18:16:00Z","finalizedAt":"2026-11-20T18:19:00Z"`. An empty response is `{"tickets":[],"nextCursor":null,"snapshotAsOf":"2026-11-20T18:20:00Z"}`. The exact KitchenTicket schema remains shared across responses.

## Staff authentication proposal

Use one staff identity authority on the shared Spring Boot server. Keep the current Manager cookie/CSRF chain scoped to the Admin workspace during transition. Add staff bearer enforcement for the Kitchen API and STOMP through Admin-owned security configuration if the team approves the draft bearer approach. Use the same staff directory and role/restaurant mapping for both chains. If owners instead choose cookies for Kitchen, amend the REST and STOMP contract together and define session expiry, CSRF for unsafe REST and STOMP CONNECT, and origin rules. A Manager cookie cannot be assumed to satisfy the draft staff bearer scheme.

| Identity | Proposed Kitchen permissions |
| --- | --- |
| KITCHEN_STAFF | Scoped ticket and history reads, permitted preparation actions, assistance reads and lifecycle actions, and food-feedback read access. Handoff only with delegated permission. |
| SUPERVISOR | Kitchen permissions plus apply/remove Rush. Allergy review only with explicit permission. |
| MANAGER | Supervisor capabilities where assigned, with separate Admin access controlled by Admin's chain. |

Admin owns issuing, validating, expiring and revoking credentials and maps the authenticated principal to an immutable staff ID. Agree on supervisor provisioning, token format and lifecycle, and sensitive audit visibility. Spring Security and server service rules derive identity and permissions. A client never sends an acting ID or role in a write. The Kitchen launcher retains its local selector only in explicit local mode; production uses the authenticated session and does not switch to the local provider on network failure.

## STOMP and synchronization proposal

Keep STOMP 1.2 over WSS at `/ws/v1`. Send `authorization: Bearer <credential>` on STOMP CONNECT through the approved authentication handler. Negotiate heartbeats, with 10 seconds in each direction proposed. Authorize CONNECT and every subscription by principal and scoped permission. Only the server publishes to private queues.

| Destination | Events and schema |
| --- | --- |
| Existing `/user/queue/v1/kitchen-orders` | `ORDER_CREATED`, `ORDER_UPDATED`. Keep fields `schemaVersion:1`, `eventId` ID, `occurredAt` UTC, `type`, `orderId` ID, `sessionId` ID, `orderVersion` Version. |
| Existing `/user/queue/v1/order-status` | `ORDER_UPDATED` only for Customer principals authorized for that session. Same schema. |
| Proposed `/user/queue/v1/kitchen-assistance` | `ASSISTANCE_CREATED`, `ASSISTANCE_UPDATED`. Common envelope `schemaVersion,eventId,occurredAt,type` plus `requestId` ID and `requestVersion` Version. |
| Proposed `/user/queue/v1/kitchen-workload` | `STATION_CAPACITY_CHANGED`, `KITCHEN_QUEUE_CHANGED`. Common envelope plus `stationId` ID and `workloadVersion` Version, monotonic per station across both types. |
| Existing `/user/queue/v1/wait-estimates` | Keep `WAIT_ESTIMATE_INVALIDATED` with common envelope. Customer refreshes the current draft estimate. |

`{"schemaVersion":1,"eventId":"00000000-0000-4000-8000-000000000041","occurredAt":"2026-11-20T18:03:00Z","type":"ORDER_UPDATED","orderId":"00000000-0000-4000-8000-000000000010","sessionId":"00000000-0000-4000-8000-000000000020","orderVersion":2}`

Publish only after commit using a persisted outbox and at-least-once delivery. Keep customer notes, allergy details, prices and staff reasons out of events. Use a bounded recent event-ID cache and monotonic applied resource versions. Treat events as invalidations; fetch authoritative snapshots rather than reconstructing order state from events. For a gap or an order leaving the active queue, fetch the scoped ticket or reload the active list as appropriate. Coalesce bursts.

On reconnect, use exponential backoff starting at 1 second, capped at 30 seconds with jitter, and pause automatic reconnect on auth rejection until reauthentication. Subscribe with a STOMP receipt and start buffering immediately, load all pages of one snapshot, atomically replace the cached collection, then reconcile buffered newer resource versions. Keep receiving as the buffer drains. Restart on disconnect, cursor expiry or buffer overflow. For Assistance, add the equivalent paginated read when that contract ships. Start connected status only after authenticated communication succeeds; distinguish syncing/stale data until authoritative reload and subscriptions succeed. Never mark an unacknowledged mutation as successful. On timeout, reconcile before offering retry; retain unresolved intent for the operator. Customer separately reloads session orders and the bill. No JSON event is a database record.

## Separate proposals after the first slice

All routes below are relative to `/api/v1`. Proposed paginated responses use `{items,nextCursor,snapshotAsOf}` with the ticket cursor rules and the resource array named `items`. Agree on exact operation and schema names before publishing. Terminal order history is defined separately above.

### Assistance

- `POST /assistance` uses terminal authentication, an authorized open session and required UUID `Idempotency-Key` scoped to authenticated terminal and session. Body has `sessionId` ID, `type` enum `WAITER,DRINKING_WATER,NAPKINS,CUTLERY,BILL_HELP,ORDER_HELP,OTHER`, and optional `note` string 0–240. Resolve the table/pickup on the server. Return 201 on create or 200 on identical replay. Return a conflict if the same key carries changed input. It creates no order or bill line.
- `GET /staff/assistance` requires scoped staff assistance permission. Optional `status` enum `PENDING,IN_PROGRESS,RESOLVED` and pagination. Order by `createdAt` descending then ID. `PATCH /staff/assistance/{id}` requires that same permission and body `{status,expectedVersion}`; target `IN_PROGRESS` acknowledges PENDING, target `RESOLVED` resolves IN_PROGRESS. Return 200 with the full request. Reject repeats and skipped states. Derive the actor and both timestamps on the server. If Customer needs to track a request after submission, propose `GET /assistance/{id}` under its session scope with the same record minus internal staff IDs, subject to review.
- Staff read fields: `id`, `sessionId` IDs, `serviceDestination` string 1–60, `type` and `status` enums above, `note` string 0–240 (empty if absent), `createdAt` UTC, `acknowledgedAt` and `resolvedAt` nullable UTC, `acknowledgedByStaffId` and `resolvedByStaffId` nullable IDs, and `version` Version. Pending has all transition fields null. Preserve acknowledgment when resolving.

```json
{"sessionId":"00000000-0000-4000-8000-000000000020","type":"DRINKING_WATER","note":""}
```

```json
{"status":"IN_PROGRESS","expectedVersion":1}
```

```json
{"status":"RESOLVED","expectedVersion":2}
```

```json
{"schemaVersion":1,"eventId":"00000000-0000-4000-8000-000000000042","occurredAt":"2026-11-20T18:04:00Z","type":"ASSISTANCE_UPDATED","requestId":"00000000-0000-4000-8000-000000000051","requestVersion":2}
```

### Food feedback

- Proposed `GET /kitchen/reviews` with staff feedback-read permission, optional `rating` integer 1–5, `topic` one of `FOOD_QUALITY,TASTE,TEMPERATURE,ORDER_ACCURACY,PREPARATION_SPEED`, and pagination. Kitchen sees scoped food-related records, newest first (ID tie-break). Customer submission at proposed `POST /reviews` remains Customer-owned; agree session eligibility, idempotency, privacy and Admin view with both owners. This feed is read-only and preserves the separate allergy-response schema and route.
- Each `FoodFeedback` item has `id`, `sessionId` IDs, `rating` integer 1–5, `submittedAt` UTC, `topics` array 1–5 of unique food-topic enums above, and nullable `comment` string 0–1000. Return only records with relevant food tags. Confirm comment visibility and redaction policy before exposing mixed-topic free text. Compute displayed count and average over the loaded filtered dataset; if aggregating a whole server result later, return an explicit snapshot-bound aggregate.

```json
{"items":[{"id":"00000000-0000-4000-8000-000000000061","sessionId":"00000000-0000-4000-8000-000000000020","rating":4,"submittedAt":"2026-11-20T18:30:00Z","topics":["FOOD_QUALITY","TEMPERATURE"],"comment":"Great flavor; it arrived a little cool."}],"nextCursor":null,"snapshotAsOf":"2026-11-20T18:32:00Z"}
```

### Staffing, preparation profiles and estimates

- **Chef availability.** Reuse Admin's `GET /admin/stations` and `PATCH /admin/stations/{id}`, with the existing station ID, name, active flag and version. Keep integer `qualifiedAvailableChefs` and `parallelSlots`, both 0–1000. Slot capacity for an active station is the smaller configured value. Inactive or zero capacity contributes no slots. A Manager with station-config permission writes; estimator reads shared state inside Spring Boot. No individual roster is required in the first contract.
- **Preparation duration and batch capacity.** Extend `PrepTime` and `PrepTimeUpdate` with required `batchCapacity` integer 1–99, default 1 for existing stored profiles during migration. Keep existing `menuItemId` response, `stationId`, `durationSeconds` (1–86400), and versions (propose reserving `expectedVersion=0` for create, consistent with the draft allowing zero). Propose duration per compatible batch, with options able to alter the effective preparation profile through the shared catalog. Never combine incompatible modifiers or allergy handling. Define a missing profile as unavailable rather than guessing. Manager changes write through existing Admin operations. Changes increase station workload version and invalidate affected estimates.
- **Pre-order estimate.** Keep draft `POST /wait-estimates` and `{sessionId,lines:DraftLine[]}` with terminal and open-session authorization. Schedule the proposed quantities into batches using approved capacity and preparation times. Account for nonpreemptible in-progress work and remaining time, existing queued work, approved Rush then Delayed then Normal with oldest first in each group, and available station slots. A new draft has no Rush entitlement or reserved slot. Use the latest finish across its required batches and apply the approved uncertainty policy to create an estimate range. If a required profile or reliable workload is missing, capacity is absent, or risk requires review, use the existing `UNAVAILABLE` reason enums. Keep `AVAILABLE` fields `minimumMinutes`, `maximumMinutes` (integers 0–1440, min ≤ max), `calculatedAt` and `validUntil` UTC (expiry later than calculation). Keep `UNAVAILABLE` fields `reason` and `calculatedAt`. Return service failure as a Problem. Spring Boot owns all calculations; JavaFX presents the range and invalidates it on draft edits, expiry, outage or workload notifications.

```json
{"qualifiedAvailableChefs":2,"parallelSlots":3,"expectedVersion":4}
```

```json
{"stationId":"00000000-0000-4000-8000-000000000016","durationSeconds":480,"batchCapacity":3,"expectedVersion":2}
```

```json
{"state":"AVAILABLE","minimumMinutes":12,"maximumMinutes":18,"calculatedAt":"2026-11-20T18:01:00Z","validUntil":"2026-11-20T18:02:00Z"}
```

```json
{"schemaVersion":1,"eventId":"00000000-0000-4000-8000-000000000043","occurredAt":"2026-11-20T18:04:00Z","type":"STATION_CAPACITY_CHANGED","stationId":"00000000-0000-4000-8000-000000000016","workloadVersion":5}
```

## First executable integration slice

1. **Infrastructure** provisions one shared server and PostgreSQL test database, migrations, a test terminal and restaurant-scoped staff credential, a catalog item with station/profile and resolved safety metadata, and an open Dine-in session through the agreed workflow.
2. **Customer** quotes and submits the single item through existing operations. Persist the order, item snapshots, idempotency record and outbox transactionally. Return 201 with the server-generated order ID. Repeating the same request and key returns the same ID and one stored order. Exclude WebSocket delivery from the first test so it isolates authoritative REST and storage.
3. **Kitchen** loads the saved ticket using staff credentials and matches order and item UUIDs, scope and versions. Update the item to PREPARING, then READY using the returned item version. Customer reads the same persisted states through `GET /orders/{id}`. After READY, assert the active ticket appears in Handoff without a terminal timestamp or a SERVED/COLLECTED outcome.
4. Perform network work off the JavaFX thread. Disable conflicting writes in flight, refresh on version or state conflict, display actionable Problems, and leave operator intent unresolved until an ambiguous response is reconciled. Keep the local tests available explicitly. No changes are required to the approved UI while the contract is under review.

### Acceptance tests

- Validate the agreed OpenAPI as 3.1 and validate examples against the shared schemas before implementation. Test persisted transitions across separate requests and after reading from the database again. Include same-key replay, changed body under same key, no duplicate persistence, wrong-restaurant/role access, stale version, illegal jump, and a held allergy line blocked even when Rush is set.
- Test mixed-station/quantities and cancelled-line derivation. Verify that READY is visible for handoff and terminal history changes only after an explicit outcome. Verify that Supervisor/Manager approval is server-enforced with an effective audit actor and current version. Expand to explicit handoff/history, assistance, reviews and estimates after the initial slice.
- Test authenticated STOMP delivery after commit, duplicate/out-of-order events, missed updates during offline periods, cursor expiry, events arriving between pages and reconciliation after ambiguous writes. Test expired credentials and rejected subscriptions.
- Complete the [two-PC Windows rehearsal](two-pc-deployment.md) using two physical machines. PC 1 runs Customer independently; PC 2 runs Kitchen/Admin, one Spring Boot instance and loopback-only PostgreSQL. Use the agreed configurable `ROSE_BACKEND_URL` for HTTPS and WSS with certificate validation. Verify order submission, returning preparation updates without manual reload, actual network interruption, recovery to the same order/version and an inaccessible database port from PC 1.

## Ownership, unresolved decisions and blocking dependencies

| Owner | Approval or delivery required |
| --- | --- |
| Customer | Review the delta and publish changes only after joint approval. Own terminal sessions, quote/submission, order reads, Customer responses, Customer-facing event/recovery consumption and submission outbox writes. Confirm feedback and Assistance submission responsibilities with Kitchen. |
| Admin/infrastructure | Own shared Spring Boot, PostgreSQL/migrations, identity authority, credential format/provisioning, role and permission mapping, catalog and station/profile IDs, LAN TLS setup and module assembly. Confirm shared database/order schema and how Manager mode and Kitchen share one launcher. |
| Kitchen | Propose staff-side projections and transitions, broker wiring and adapter/recovery design. After approval, implement the Kitchen-owned server operations in the shared server and real Kitchen client adapters. Coordinate schema migrations with Admin and order aggregation with Customer. |

**Before the first slice:** agree on the accepted draft revision, canonical UUID validation, staff and terminal credential provisioning and scope, ticket station/display snapshot, version/conflict semantics, and the single shared server/database fixture. Verify catalog IDs and allergen data. Align Gradle/module ownership before editing the shared launchers. If the Customer UI requires a wait estimate before submission, agree on the existing unavailable-estimate handling for the fixture.

**Before later slices:** settle allergy-review and handoff permissions, substitution repricing, history retention, delayed-state policy, cursor limits, broker/outbox ownership, Assistance and feedback privacy and API ownership, and estimator uncertainty and invalidation policies. Treat all new paths and fields above as proposals until the owning workstreams approve and publish the revised contract.
