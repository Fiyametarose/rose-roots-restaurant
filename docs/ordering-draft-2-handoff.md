# Ordering draft 2 handoff

[OpenAPI 1.0.0-draft.2](ordering-api-v1.openapi.json) is the shared contract for the Chef decision and Assistance slices. This revision records the interfaces ahead of rollout.

## Chef decisions

Quote and first submission return HTTP 422 with `ALLERGEN_CONFLICT` and a `violations` entry for each affected draft line. The body uses JSON Pointer `/lines/{index}` and names the matching catalog allergen. A visit declaration applies to every line, together with any line declaration. A declaration with no known ingredient or selected option match still creates a preparation hold.

`POST /api/v1/kitchen/items/{id}/allergy-review` accepts the existing `StaffReview` shape. The first rollout accepts `APPROVE` and `DECLINE`. The bearer must identify an individual Kitchen staff member with the separately provisioned review permission. A Manager cookie grants no access to this route. Decisions require the current item version and a nonblank reason. The reason is private audit data. Customer uses the existing order and bill reads for the safe `reviewMessage`, new version, and adjusted payable amounts.

An unstarted held item can become `APPROVED_FOR_PREPARATION` and stay `RECEIVED`, or become `DECLINED` and `CANCELLED` with `billable=false`. A stale decision returns `409 VERSION_CONFLICT`. Re-read after an uncertain write. An incompatible state returns `409 INVALID_TRANSITION`. A staff credential without review permission receives `403 FORBIDDEN`. The backend never interprets Rush as safety clearance.

## Assistance

Customer maps its seven types directly to `AssistanceType`. Kitchen displays wire `BILL` as **Bill Help**. Send `note` only for `OTHER`, and limit it to 240 characters. The backend assigns the request UUID, timestamps and destination. Customer supplies a session UUID and a UUID `Idempotency-Key`. Retry with the same body and key after an uncertain result. Reusing a key with changed input returns `409 IDEMPOTENCY_CONFLICT`. A matching request still committing returns `409 IDEMPOTENCY_IN_PROGRESS`.

Status moves one step at a time through `SUBMITTED`, `ACKNOWLEDGED`, `IN_PROGRESS`, and `COMPLETED`. Customer acceptance is `SUBMITTED`. Staff actions use the request version and advance to the next state. They return `409 VERSION_CONFLICT` for an outdated version and `409 INVALID_TRANSITION` for a skipped or terminal transition.

Both list operations return `requests` and a nullable opaque `nextCursor`. Customer reads the terminal-owned session collection. Kitchen reads its restaurant collection. Both project the same request UUID and state. They omit private actor audit details. REST polling is sufficient for the first rollout.

## Provisioning and deployment

Flyway V7 adds the Chef permission and allergy audit. V8 adds Assistance requests and their transition audit in the same PostgreSQL database. The existing terminal, staff, and Manager sign-in setup applies. [Credential provisioning and TLS](ordering-backend-4c2a.md)

Provision a distinct `KITCHEN` credential for each person. After confirming the Chef's qualification, set `ordering_credential.allergy_reviewer=true` for that credential UUID through the operator database connection. New credentials start with the permission off. Keep the credential-to-person assignment in the provisioning records. The review audit retains the credential UUID, decision, reason, and versions.

With Flyway V11, a signed-in Manager can provision managed accounts from **Staff & Security**. Choose Chef and grant allergy review only after confirming the individual's qualification. Copy the issued bearer into `ROSE_KITCHEN_STAFF_TOKEN` for that staff member. The bearer is displayed once and expires after 90 days. Manager accounts sign in with their own password through the existing Admin form. Permission edits require the current account version. Disabling a managed account blocks its bearer and Admin session on subsequent requests. Existing operator-issued credentials still use their original grants.

Catalog operators can record known option allergens in the Manager customization editor using `Name | price adjustment | MILK, EGGS`. The optional third column uses the existing catalog allergen codes. Records without declared allergens still need a Chef decision when a declared Customer allergy triggers a preparation hold.
