# Waiter accounts and service handoff

In Manager **Staff & Security**, create a **WAITER** account with a password. Use **Set staff password** for an existing account. Waiters enter their own username and password under **Staff sign-in** in the Kitchen app. The existing personal session expires after 30 minutes and can be locked or switched at any time. Disabling the account or resetting its password revokes its existing sessions.

Everyone at a provisioned Kitchen workstation can view **Handoff** without signing in. Only a Waiter session can confirm service. Check the table number for dine-in or the pickup reference for takeaway, then explicitly confirm **Served** or **Collected**. Chef preparation ends at READY. Managers and Supervisors retain their existing operational oversight and can review completed handoffs through the authorized order views.

Flyway V20 adds the Waiter role and personal actor fields to `order_handoff`. Existing handoff records remain intact and are shown as **Unknown (legacy record)** where a verified personal actor was never recorded. Item preparation history retains the responsible Chef and records the Waiter as the handoff actor.

Ordering API 1.0.0-draft.6 adds WAITER to the personal-session response and requires a personal Waiter bearer for the existing `POST /api/v1/kitchen/orders/{id}/handoff`. The existing `status` and `expectedVersion` payload is unchanged. Handoff checks the restaurant, service mode, order version, and that all remaining items are READY and allergy-cleared. Kitchen history and handoff responses expose the audit under `handoff`. Customer order responses retain their existing status payload and omit staff audit data. Review eligibility still follows SERVED or COLLECTED.
