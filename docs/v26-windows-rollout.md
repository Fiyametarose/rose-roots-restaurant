# V26 Windows rollout

Apply V26 during an approved maintenance window. Keep the installed V25 server, Customer and Kitchen modules, profile backups and protected launch settings available for recovery.

## Before the window

1. Build the published V26 release with Java 21. Record the release SHA and SHA-256 hashes for the server JAR and both Windows distributions. Keep packages and operational evidence outside Git and synced folders.
2. Inventory the live database and identify the open Table 01 test visit and its outstanding balance. Capture bill versions, order and item states, payment attempts, receipts and the existing restaurant reviews as part of the inventory.
3. Rehearse restoring a V25 custom-format dump into an isolated PostgreSQL 17 instance. Apply Flyway V26 there and verify existing table contents, Manager recovery over trusted HTTPS and restaurant-level review access. V26 adds the food-feedback table and its constraints. Existing reviews stay in `visit_review`. Expect no dish or order feedback until a Customer submits it.

## Approved cutover

1. Close the installed desktop clients and stop the V25 backend on port 8090. Confirm that no application connection can write and that no database transaction is unresolved. Preserve open visits and outstanding balances as they are.
2. Take a fresh V25 custom-format dump. Verify its checksum and independently restore it before starting a V26 server against the persistent database. Stop if either the restore or trusted Manager recovery fails.
3. Start the verified V26 server on a temporary loopback HTTPS port with the existing protected configuration. Confirm Flyway V26, the historical inventory and the same Table 01 bill and visit state. Sign in with the existing Manager. Stop the temporary listener and start one V26 backend on the original port.
4. Update only the module paths for the three Customer profiles and the Kitchen profile. Preserve DPAPI credentials, origins, Java trust stores, certificate and shortcut targets. Reopen the existing shortcuts and confirm successful connections.
5. Check the separate Restaurant and Dish/Order reviews views in the packaged clients. Keep destructive acceptance workflows on disposable PostgreSQL. Any authorized live test review remains an operational record.

## Recovery

Stop V26 and preserve logs. Before restarting V25, restore the verified final V25 backup and revert the four profile module paths. If V26 already accepted operational writes, stop and reconcile those writes before deciding whether to restore. Always keep the original protected configuration and archives restricted.

## API compatibility

Draft.10 adds two terminal-owned submission routes under an exact visit and order, and a read-only food feedback feed for each staff interface. Order readback adds eligibility and saved ratings at both order and line level. The existing visit review and follow-up calls retain their original behavior.

- Send a UUID idempotency key with each submission. Replaying the same normalized payload with the same key returns the saved rating. A changed payload with that key returns `IDEMPOTENCY_CONFLICT`. A different key for an already reviewed target returns `REVIEW_ALREADY_EXISTS`.
- Dish feedback accepts `{"rating":5,"comment":"Well prepared."}`. Order feedback also accepts matching food and order topics, for example `{"rating":4,"topics":["ORDER_ACCURACY"],"comment":"Everything arrived as ordered."}`. Comments are plain text and limited to 500 characters.
- A Customer sees `REVIEW_NOT_ELIGIBLE` for unserved, uncollected or cancelled targets. Access uses the existing owning-terminal visit rules, including for authorized historical visits.
- Newly submitted dish and order comments are shared with Kitchen and Managers. Existing restaurant review privacy and follow-up are unchanged. Dish attribution uses the accepted pricing snapshot for the name and the accepted menu identifier for aggregation. The Customer uses the current menu image where available.
