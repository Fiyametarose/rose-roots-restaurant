# Phase 4B contract change summary for Customer and Admin

**Review request.** This is a proposed delta to Customer's OpenAPI 3.1 draft. The authoritative file and server are unchanged. See the [detailed Kitchen proposal](phase-4b-kitchen-contract-proposal.md) for schemas, role rules, payloads, and acceptance tests.

| Area | Keep from the Customer draft | Propose for joint approval |
| --- | --- | --- |
| Core ordering | Customer quote/submit/read, canonical order and line IDs, UTC, snapshot prices, idempotent submission | Align UUID v4 validation, keep local numeric IDs isolated, and agree on the order reference and station snapshot fields. |
| Kitchen ticket | Existing nested Order, service destination, priority and delayed | Add display reference, item station data, preparation and terminal timestamps, configured delay target, and latest Rush audit metadata. Add single-ticket read and stable snapshot pagination. |
| Preparation, allergy, Rush | Existing item transition, allergy-review, and Rush paths and expected versions | Narrow item mutation to preparation only. Define safety gates and reviewer permissions. Approve scoped Supervisor/Manager Rush and append-only audit, preserving preparation state. |
| Handoff and history | Distinct READY, SERVED, COLLECTED and CANCELLED states | Add order-level handoff and terminal history endpoints. READY remains in the active feed and eligible for Handoff. |
| Staff security | Shared server and existing protected Admin mode | Decide Admin cookie/CSRF isolation versus staff bearer scope on one Spring Security authority, including provisioning, expiry, and an approved role and permission matrix. No staff ID or role in mutation requests. |
| Real-time delivery | Existing private STOMP order destinations and order/version events | Add future Assistance and workload events. Agree snapshot recovery, event deduplication, bounded retries and auth rejection handling. |
| Later APIs | Existing station capacity, prep profiles and wait estimate request/response | Add batch capacity, confirm server estimation and invalidation rules, define separately versioned Assistance and read-only food feedback interfaces, and agree retention and privacy. |

**Customer review:** Approve the shared Order boundary, Customer status re-read, idempotency expectations, safety response flow, and shared submission ownership. Confirm the feedback workflow remains separate from allergy responses.

**Admin review:** Approve shared security and role mapping, server/schema ownership, catalog and station IDs, audit visibility, and one integrated staff launcher. Keep the configurable LAN origin as `ROSE_BACKEND_URL`, with trusted HTTPS/WSS and loopback-only PostgreSQL on PC 2.

**Kitchen next step after approval:** Implement only the first REST slice. One persisted Customer submission appears under the same ID in Kitchen, a staff principal moves its item to PREPARING then READY using versions, Customer reads both states, and Handoff shows Ready without finalizing service. Follow with post-commit STOMP and authenticated recovery. The [two-PC physical demonstration](two-pc-deployment.md) remains an acceptance gate.
