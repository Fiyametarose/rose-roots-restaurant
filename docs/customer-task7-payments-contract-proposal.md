# Task 7 shared payment and receipt proposal

**For Integrated Codex review.** This document proposes a contract. The ordering OpenAPI, production routes and providers remain unchanged. Spring Boot and the shared PostgreSQL database own balances, attempts, provider reconciliation and receipts. Customer and Staff consume scoped projections from that backend.

## Balance and settlement rules

- Use the existing KES integer minor units and validated order snapshots. Return server-calculated bill revision, outstanding minor amount, currency, payable order IDs and amounts, settled amount, and whether checkout is blocked. Customer sends the last reviewed bill revision and a method. The backend derives the amount and never accepts a desktop-calculated amount.
- Require an explicit refresh/review before initiating a payment after a bill revision conflict. Serialize payment initiation with bill updates, order submission, and other attempts for the same visit. For the first slice, propose one pending full-balance attempt per session. Split payments, tips, credits, refunds and fiscal receipts require separate decisions.
- Orders awaiting Chef review, clarification, deferral or substitution approval block full-session settlement. Expose the blocked reason without disclosing allergy notes in the payment projection. On decline, retain the line in history, exclude it from the bill and publish the new balance revision. Chef approval affects preparation eligibility only.
- Freeze the reviewed payable snapshot while an attempt may still settle. Defer a new order round during that window, or require an explicitly agreed ledger policy before supporting concurrent changes. Never overwrite a previously issued receipt when a later event changes an amount. Unexpected late settlements or reversals require reconciliation and a separate audit entry.
- Explicit session closing checks authoritative outstanding balance, unresolved payment attempts, pending Chef decisions and agreed handoff/fulfilment requirements. Session expiry does not cancel money already in flight. Preserve the existing handoff-based review entitlement for the owning terminal after settlement or closure, subject to the agreed retention policy. Payment alone does not grant review eligibility.

## Candidate operations and fields

Candidate paths below are relative to `/api/v1`. No implementation should bind these paths until owners ratify them.

| Operation | Proposed authorization and result |
| --- | --- |
| `GET /sessions/{id}/payable-balance` | Owning terminal or appropriately scoped staff. Returns server revision, itemized payable snapshot, blocked reasons, outstanding amount and current open attempt reference. |
| `POST /sessions/{id}/payments` | Owning terminal. `Idempotency-Key` required. Body contains `method`, `expectedBillVersion`, and method-specific input only. optional M-Pesa phone if required, no PAN, CVV, staff identity, amount or provider secrets. 201 for initial persisted attempt, 200 for identical replay. Respond with current attempt ID/status and only safe customer action data. |
| `GET /sessions/{id}/payments/{paymentId}` | Owning terminal or scoped staff. Returns authoritative current status, safe reason, timestamps, version, method, exact amount and receipt reference once issued. Supports recovery on timeout, restart, browser return and reconnect. |
| `POST /sessions/{id}/payments/{paymentId}/cancel` | Owner-scoped and versioned. Records the intent. Report cancelled only when the backend determines settlement can no longer occur. Otherwise keep the attempt pending. |
| `POST /staff/payments/{paymentId}/confirm-cash` | Authorized cashier in the same restaurant, with current payment and balance revisions. Require the cashier to verify collected cash and return change before recording payment. Actor, timestamp and audit are server-derived. Reject terminal credentials. |
| `GET /sessions/{id}/receipts/{receiptId}` | Owning terminal or scoped staff. Returns immutable server-issued receipt containing stable ID/reference, payment method/reference masked as needed, verified timestamp, currency, itemized paid snapshot and totals. An order acceptance view remains distinct from a paid receipt. |
| Provider callbacks | Backend-only endpoints selected after provider approval. Validate or reconcile each callback using the chosen provider protocol. Verify provider account/reference, amount, currency and terminal outcome before recording settlement. Deduplicate event and transaction identities. |

Use existing scoped Problem responses. Proposed conflict distinctions include stale balance, a payment still pending, mismatched idempotency key and an already paid visit. Ratify exact codes and operation names with Integrated Codex. An idempotency record must survive provider and network timeouts. Same key and canonical payload recover the same attempt. Changed payload or method requires an explicitly new attempt only after prior financial exposure is resolved. Use a provider idempotency key where supported, store correlation identifiers and deduplicate duplicate and out-of-order callbacks. Creation, ledger change, receipt generation and any event notification are transactional at the relevant boundary. Provider network calls run outside database transactions via an approved durable dispatch/reconciliation mechanism.

## Payment states

| State | Meaning and UI behavior |
| --- | --- |
| `INITIATED` | Durable attempt created. Provider/cash confirmation has yet to begin. |
| `PENDING` | Awaiting provider, actionable customer step, cashier, or reconciliation. A timeout, cancellation request or closed hosted tab can leave the outcome uncertain. Continue reading the same attempt. |
| `SUCCEEDED` | Exact verified provider settlement or authorized cash confirmation committed. Update the outstanding balance and issue a single receipt for the paid snapshot. |
| `FAILED` | The provider/backend has confirmed the attempt cannot settle. Offer a new attempt if a balance remains. |
| `CANCELLED` | Cancellation is authoritative and the attempt cannot settle. Offer a new attempt if a balance remains. |

Do not infer success from a browser return, prompt delivery, customer statement that cash was paid, unauthenticated callback, or a timeout. Payment state invalidation can be polled via REST first and later added to private events after approval. Keep PII, phone numbers and provider payloads outside customer logs and events.

## Provider and deployment dependencies

- **M-Pesa candidate** Safaricom Daraja M-Pesa Express/STK Push. Admin needs an approved sandbox app with OAuth consumer key/secret, the assigned M-Pesa business shortcode and STK passkey, test MSISDN/merchant test setup, and a provider-reachable HTTPS callback and status-query plan. Confirm the exact products enabled for the merchant, currency/whole-shilling constraints and production onboarding before implementing. Keep secrets and provider payloads on Spring Boot. The sandbox exercises the protocol and does not prove a real transfer. [Safaricom Daraja](https://developer.safaricom.co.ke/)
- **Card candidate** A hosted checkout or certified terminal from a merchant-approved processor. Stripe Checkout is an example for evaluation, subject to merchant-country and KES acceptance. Its test integration would require a provider test account, backend test secret, configured hosted return/cancel URLs, webhook endpoint and its separate signing secret, plus published test card methods. Spring Boot verifies the provider payment state independently of redirects. JavaFX should never collect raw card numbers, CVV or PIN. [Stripe hosted Checkout](https://docs.stripe.com/payments/checkout) · [Stripe webhook verification](https://docs.stripe.com/webhooks/signature)
- **Cash** Requires an authenticated scoped cashier role, amount/change procedure and durable audit. No external payment credentials. Validate duplicate button presses, concurrent staff writes and wrong restaurant scope.
- **Deployment** The LAN Customer/Staff origin remains private. Provider callbacks typically need externally reachable trusted HTTPS through an Admin-managed ingress or sandbox tunnel. Expose only approved callback routes and keep the database private. Use distinct sandbox/live accounts, credentials and receipt presentation. Availability and agent authorization for a provider have yet to be established.

## Integration review and acceptance

Admin owns provider selection, secret storage, callback ingress, persistence, cashier permissions and fiscal/settlement requirements. Kitchen confirms what unresolved Chef statuses block settlement and publishes appropriate state changes. Customer owns interaction and receipt presentation after the wire contract is approved. Integrated Codex coordinates one versioned OpenAPI revision, method/eligibility errors, bill locking, issue/receipt policy and first provider selection. No wire adapter or provider simulation ships in this proposal.

Test concurrent starts, same-key replay after timeout, duplicate webhook, out-of-order notification, exact amount/currency match, cash confirmation scope, Chef holds/declines, failed/cancelled flows, and one paid receipt after verified settlement. Independently verify that review eligibility survives closing and that a simulated, sandbox or fixture transaction cannot appear as a genuine live payment.
