# Admin Phase 3A Orders & Payments

## Read-only audit

The Customer checkout on `feature/customer` was at `e5f58fa`.
Its `Ordering`, session history and bill models, `SpringBootOrderService`,
and `OrderStatusSocket` define client adapters and a proposed contract in
`docs/ordering-api-v1.openapi.json` and `docs/ordering-integration-v1.md`.
The Customer contract covers terminal-scoped dine-in and takeaway sessions,
server-accepted order rounds, item quantities, option selections and
instructions, price snapshots in integer minor units, and combined bills.
Its proposed states include RECEIVED, PREPARING, READY, SERVED, COLLECTED
and CANCELLED. The Customer payment, settlement and refund contract is
outside that ordering draft.

The Kitchen checkout was at `502f704` with an expanded local `kitchen-app`
working tree. It contains local ticket, item preparation, review hold, Rush,
delay and handoff handling. Its
`kitchen-app/docs/phase-4b-kitchen-contract-proposal.md` proposes authorized
Kitchen transitions, served/collected handoff and private WebSocket updates.
No production database-backed order or payment implementation was found
in either checkout or this Admin server. The available Spring Boot features
are Manager authentication, catalog and image operations, and the catalog
dashboard projection.

Project Plan 1.1 names pay-at-counter cash, M-Pesa and card simulation,
pending/successful/failed/cancelled/refunded states, an authorized staff
cash confirmation, and issued receipts. Those are planned responsibilities.
The plan's provisional `/payments/simulate`, `/payments/{id}` and
`/receipts/{id}` routes have no implementations in the audited checkouts.
Kitchen preparation outcomes do not confirm payment. Both source checkouts
were inspected without changes.

## Manager projection contract for team review

The existing server exposes two Manager-session reads. These routes use
the existing HTTPS origin, Manager session, and `/api/v1/admin/**` security.
Neither terminal credentials nor Kitchen Staff profiles grant access.
Responses send `Cache-Control: no-store`. There are no financial mutation
routes. Cash confirmation, refunds, cancellations and receipt issuance
require separate owner-approved contracts and authorization.

| Endpoint | Contract |
| --- | --- |
| `GET /api/v1/admin/orders` | Optional `q` (up to 120 characters), `from` and `to` (ISO inclusive business dates), `orderStatus`, `paymentStatus`, `serviceType`, `paymentMethod`, `sort`, `cursor`, and `limit` (default 25, range 1–100). `sort` accepts `NEWEST`, `OLDEST`, `TOTAL_DESC`, `TOTAL_ASC`, or `ORDER_NUMBER`. |
| `GET /api/v1/admin/orders/{id}` | One Manager order projection with payment and receipt details. The order ID is a backend UUID. |

An unavailable owner implementation returns HTTP 503 with
`code: OPERATIONS_SOURCE_UNAVAILABLE`. A genuine successful empty query
returns an empty `items` array. Explicit zero metrics are measured zeros.
Null metrics or detail fields mean unavailable. Session failures return
401 or 403 and the desktop clears the manager session. An unknown order
returns 404 when a provider is connected.

The Java interface [OperationsSource](../server/src/main/java/com/roseroots/server/admin/OperationsSource.java)
is the shared backend integration boundary. Add a single provider backed
by the authoritative services. There is no default production provider.
The two views consume these projections:

- **Page.** `currency`, `summary`, `paymentMethods`, `items`, `nextCursor`.
  Each item has `id`, `orderNumber`, `submittedAt`, `serviceReference`,
  `serviceType` (`DINE_IN` or `TAKEAWAY`), `itemCount`, `totalMinor`,
  `orderStatus` and `paymentStatus`. Use a stable ordering with UUID as a
  tie-breaker and bind cursors to the filters and snapshot. Return UTC ISO
  instants, and interpret inclusive date filters in the restaurant business
  timezone. JavaFX displays submitted and payment times in the PC's timezone.
- **Summary.** `totalOrders`, `activeOrders`, `completedOrders`,
  `confirmedSalesMinor`, `outstandingMinor`, `pendingPayments` and
  `failedPayments` apply to the full filtered result, independent of page
  size. Ready orders remain active until handoff; served and collected
  orders are completed. Keep cancelled orders distinct. Deduplicate
  transactions and calculate outstanding balances at the applicable billing
  scope, so a combined bill across rounds is counted once. Populate only
  metrics the owner can derive authoritatively.
- **Detail.** `currency`, `order`, `sessionId`, `items`, `history`,
  `totals`, `payments`, and optional `sessionBill`. Item names, quantities,
  base prices, selected option names and unit adjustments, and final line
  prices come from the accepted order snapshots. Totals use
  `subtotalMinor`, `serviceMinor`, `taxMinor` and `totalMinor`.
  Include permissible customer instructions and chronological item or
  order state outcomes. Admin performs no repricing or total reconstruction.
- **Payments.** Return method codes supported by the owner, such as
  `CASH`, `M_PESA`, or `CARD`, alongside the transaction UUID, amount,
  reference, timestamp, status, and `CHARGE` or `REFUND` kind. Supply the
  associated `orderId` or `sessionId` so shared bills stay distinguishable
  from individual orders. Attach only authoritative issued receipt
  number, timestamp and amount. States are proposed as `UNPAID`,
  `PENDING`, `SUCCESSFUL`, `FAILED`, `CANCELLED`, and `REFUNDED`.
  Keep cash unpaid until authoritative confirmation. Keep authentication
  secrets, raw payment credentials and restricted internal notes out of
  these projections.

Monetary fields are integer minor units in the configured ISO currency.
For KES, `135000` is KES 1,350.00. The UI converts those values using
BigDecimal for display. Confirmed sales are gross successful charges.
The supplied projection helper can compute gross totals from a complete
single-currency set of immutable settlements, excluding pending, failed
and refund records and counting each charge UUID once. It cannot calculate
net sales or outstanding balances from one page of orders.

## Integration ownership

**Member 1** supplies the order, session, accepted snapshot, bill,
transaction and receipt reads. Confirm the filters, session settlement
scope, cash permission and issued-receipt format before connecting
production. **Member 2** supplies authoritative Kitchen outcomes,
including final handoff and Rush/delay state, and coordinates Manager
read invalidations on the shared private STOMP infrastructure.
Current Admin refresh is manual. Future authorized events should prompt a
REST refresh with the existing filters, leaving REST authoritative.
Keep private instructions and monetary details out of event envelopes.

The two-PC topology and `ROSE_BACKEND_URL` configuration remain as
described in the [Windows guide](two-pc-demonstration.md). The desktop
does not connect to PostgreSQL. Phase 3A adds no tables or migrations.
End-to-end physical-PC verification requires the owner implementations.

## Verification

The 47-test baseline passed. The full Gradle build and both Windows
launcher distributions passed with 56 tests. Operational tests use a
test-only provider and exercise query routing, filtering, empty results,
pagination, exact historical money values, confirmed-only charges,
distinct order and payment states, Manager access, session invalidation
and rejected mutations. The normal backend returns the unavailable
problem response even with the persistent catalog connected.

The JavaFX smoke run used the actual launcher code over trusted local
HTTPS. It exercised the unavailable state with the packaged server and
exercised search, details, receipts, sign-out and a narrow viewport with
the separate fixture server. The fixture configuration is test source
only. No operational records were written to PostgreSQL.

- [Normal application, shared services unavailable](phase3a-screenshots/orders-payments-unavailable.png)
- [Populated orders fixture](phase3a-screenshots/orders-payments-fixture.png)
- [Historical order detail fixture](phase3a-screenshots/order-details-fixture.png)
- [Payment detail fixture](phase3a-screenshots/payment-details-fixture.png)
- [Receipt fixture](phase3a-screenshots/payment-receipt-fixture.png)
- [Narrow-window fixture](phase3a-screenshots/orders-payments-narrow-fixture.png)
