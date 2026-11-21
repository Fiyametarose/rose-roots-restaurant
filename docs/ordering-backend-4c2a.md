# Phase 4C.2A ordering backend

The shared Spring Boot server enables the ordering endpoints when the `catalog` profile is active. Flyway V6 upgrades the existing PostgreSQL schema. Use `catalog,lan` for the two-client HTTPS test. Plain HTTP ordering is limited to loopback for backend tests. [Windows database setup](database-environment.md) · [LAN and TLS setup](two-pc-demonstration.md)

## Provisioning for the first REST test

Use an operator-controlled database connection to provision a restaurant, Table 07, a terminal assigned to that table, and separate Terminal and Kitchen credentials. There is one restaurant per deployment, matching the shared catalog. The migration installs no accounts or tokens.

Generate each bearer token independently from at least 32 cryptographically random bytes. Encode it as unpadded base64url and store the SHA-256 digest of that **encoded text** in PostgreSQL. Give the original token only to its intended client through an external secret configuration. Use a distinct credential ID for each staff member and each terminal credential, and choose an explicit expiration. Setting `revoked_at` invalidates the credential for subsequent requests. Manager sign-in continues to use the existing cookie and CSRF flow.

The following is a parameterized `psql` template for a fresh ordering deployment. Supply UUIDs, expiry, and hex digests through your operator tooling or private environment. For an existing deployment, reuse its restaurant and table IDs and insert only the necessary terminal and credential rows.

```sql
\getenv restaurant_id ROSE_PROVISION_RESTAURANT_ID
\getenv table_id ROSE_PROVISION_TABLE_ID
\getenv terminal_id ROSE_PROVISION_TERMINAL_ID
\getenv terminal_credential_id ROSE_PROVISION_TERMINAL_CREDENTIAL_ID
\getenv staff_credential_id ROSE_PROVISION_STAFF_CREDENTIAL_ID
\getenv terminal_sha256 ROSE_PROVISION_TERMINAL_SHA256
\getenv staff_sha256 ROSE_PROVISION_STAFF_SHA256
\getenv expires_at ROSE_PROVISION_EXPIRES_AT
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO ordering_restaurant (id) VALUES (:'restaurant_id');
INSERT INTO ordering_table (id, restaurant_id, display_number)
VALUES (:'table_id', :'restaurant_id', '07');
INSERT INTO ordering_terminal (id, restaurant_id, table_id)
VALUES (:'terminal_id', :'restaurant_id', :'table_id');
INSERT INTO ordering_credential (id, restaurant_id, terminal_id, scope, token_sha256, expires_at)
VALUES
  (:'terminal_credential_id', :'restaurant_id', :'terminal_id', 'TERMINAL',
      decode(:'terminal_sha256', 'hex'), :'expires_at'),
  (:'staff_credential_id', :'restaurant_id', NULL, 'KITCHEN',
      decode(:'staff_sha256', 'hex'), :'expires_at');
COMMIT;
```

## Customer and Kitchen handoff

Use the unchanged [OpenAPI 1.0.0-draft.1](ordering-api-v1.openapi.json). The Customer launcher needs to attach the Terminal bearer to device, session, quote, submission, and readback requests. It should source menu items from the shared catalog and keep the same UUID idempotency key and body when retrying an uncertain submission.

Use a published KES dish with no required selections for the first order. Dish IDs come directly from `GET /api/v1/menu`. Existing canonical UUID group and option IDs pass through. For legacy catalog IDs, the deterministic adapter uses Java's `UUID.nameUUIDFromBytes` on UTF-8 text. The group input is `<menuItemId>/group/<catalogGroupId>` and the option input is `<mappedGroupId>/option/<catalogOptionId>`. This maps existing catalog identifiers to the UUID fields already required by the ordering contract. Coordinate this mapping with Customer before testing legacy customizations through JavaFX.

Sessions last eight hours and quotes last five minutes. Start or resume a session, obtain and review a quote, and submit it with a UUID `Idempotency-Key`. A successful replay returns the original accepted response. Use order or session history GET for current preparation state. The running bill sums accepted snapshots in KES with zero tax and service charges in this slice.

For Kitchen, set `ROSE_KITCHEN_MODE=connected`, `ROSE_BACKEND_URL` to the trusted HTTPS origin, and `ROSE_KITCHEN_STAFF_TOKEN` to the Kitchen bearer. Refresh tickets and advance the same item with its current version. A Customer refresh should observe PREPARING and then READY. READY stays active for Handoff. Treat the queue cursor as opaque and restart from the first page for a new refresh.

Disclosed allergy information stays on hold when the catalog cannot establish preparation safety. Use a reviewed no-allergy declaration for the initial PREPARING-to-READY test and a separate disclosed-allergy order to verify the hold. Allergy clearance, service confirmation, and automatic delivery of outbox events are outside this slice.

## Reproduce the PostgreSQL check

Point `ROSE_TEST_DB_URL`, `ROSE_TEST_DB_USERNAME`, and `ROSE_TEST_DB_PASSWORD` at disposable PostgreSQL. Run `.\gradlew.bat --no-daemon clean build` on Java 21. The ordering suite creates a dedicated schema, upgrades it from V5, runs against the real Spring Boot HTTP server, and drops the test schema afterward.
