# Customer order confirmation

Task 4B adds a read-only Order Confirmation route after Cart. It follows the split layout of the original Cart & Order Review reference. The selected dishes are on the left and the session, totals, estimate and visit disclosure are on the right. The original gallery has a separate paid receipt design. This route is before any payment or receipt.

Normal and demo launches use the disconnected order service. Both can review a local cart. Neither can create a server order. The wait estimate uses the existing service boundary and displays the unavailable message when there is no current result.

## Connecting the real backend

- Supply an authenticated, compatible Spring Boot service to the Customer composition root and use an authoritative open session. The HTTPS adapter requires a trusted `/api/v1/` base, a credential supplier and a compatibility signal. A development table assignment or pickup reference stays local.
- Load authoritative menu item, customization group and option IDs from the backend catalog. Local fixture slugs are rejected for submission. The adapter calls only the documented `POST /order-quotes` and `POST /orders` routes.
- On a connected session, the customer records or updates their visit declaration, checks prices and reviews the quoted items and allergy information. Quotes and the review checkbox must be refreshed when the draft changes or the quote expires. The server controls currency and charges. The wait-estimate service still needs its own live adapter.
- The submission coordinator keeps the immutable request and idempotency key for ambiguous outcomes. Repeating the action within the same session replays that original request. Definitive pricing or validation rejection keeps the cart and invalidates the quote. A compatible acceptance must match the session, reviewed pricing and original declarations and instructions before the client removes the unchanged submitted lines. Other cart lines remain.
- Acceptance can show lines pending kitchen review or declined and excluded from the accepted total. Kitchen delivery, later review updates and session order history need the shared backend and authenticated event integration.

## Questions for the shared contract review

- Agree on how startup establishes compatibility and acquires or renews credentials. The draft defines the bearer transport and leaves provisioning open.
- Confirm the quote and replay rules with Spring Boot using end-to-end tests, including canonical snapshots, declined totals and expiry. Agree how the terminal should recover an unresolved submission after restart. The current attempt cache lives in memory and covers retries during the running application.
- Confirm the live wait response and stale-result behavior against the Kitchen implementation. No demonstration estimate enters the running application's confirmation.

The confirmation tests exercise the workflow with an isolated in-memory service and check the HTTP codec against the shared OpenAPI examples. They do not prove connectivity to a deployment.
