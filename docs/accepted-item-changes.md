# Accepted item changes

My Orders offers Edit Item and Cancel Item for a server-eligible item in an open visit. A Chef claim alone does not freeze the item. A successful change releases the claim. The order and Kitchen feeds refresh through their existing polling.

The three POST operations under `/api/v1/sessions/{session}/orders/{order}/items/{item}` are `preview`, `edit` and `cancel`. See the [ordering API](ordering-api-v1.openapi.json). All require the original terminal's credential and the displayed order and item versions. A proposal changes only the dish, quantity, selections and instructions. The server retains the accepted allergy declarations.

Review the authoritative price from `preview` before calling `edit` with its fingerprint. The fingerprint binds the price, proposed configuration, item and order versions, bill version, payment history and preparation snapshot. If the price or bill changes, preview again. Use one UUID idempotency key for each confirmed edit or cancellation and reuse it for an interrupted request. Successful replays return the original mutation result. Poll the order for current state.

The visit, order and payment checks are transactional. Preparation, handoff and cancellations freeze the affected item. Pending or successful payments lock the visit against payable changes. Cancelling the last active item cancels the order. Cancelled items remain in history and are excluded from the payable amount and Kitchen work.

Flyway V25 records immutable before and after item snapshots and keeps the original accepted submission and subtotal. It allows the payable amount to exceed the original subtotal when the customer confirms a higher price. Edits require an active preparation station. Missing workload measurements yield an unavailable ETA through the existing estimate contract.
