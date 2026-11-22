# Shared Kitchen workstation access

Ordering API 1.0.0-draft.4 adds workstation bearer authentication to three existing operations under `/api/v1`. Customer clients keep the same endpoints, payloads, and statuses.

| Method and path | Workstation access |
| --- | --- |
| `GET /kitchen/orders` | Read the restaurant's active tickets, preparation states, stations and allergy information. |
| `GET /kitchen/assistance` | Read the restaurant's Assistance queue. |

V24 makes Assistance writes available only to personally authenticated on-duty Waiters. Supervisors can reassign unfinished work. See [staff duty](staff-duty-v24.md).

The existing seven request types, pagination and Customer session authorization remain in effect. Stale versions return `409 VERSION_CONFLICT`. An invalid step with the current version returns `409 INVALID_TRANSITION`. Reload the queue after a conflict or an uncertain response.

A workstation credential never has individual Kitchen, allergy review, Supervisor, Customer terminal, or Manager authority. It cannot write preparation, priority, handoff, payments or staff data. It cannot access Kitchen history or reviews. Unknown, expired and revoked credentials return 401. Authorized credentials used outside their scope return 403 on the ordering API. Assistance audit rows retain the credential UUID and timestamp. Historical shared transitions retain the workstation credential UUID. Personal Waiter transitions also record the staff account ID.

## Provision and rotate

1. Connect to the trusted HTTPS server. In Manager sign-in, open **Staff & Security** and issue a credential under **Shared Kitchen workstations**.
2. Transfer the issued credential through a protected channel. On the Windows workstation, enter it at the masked prompt of the [showcase Kitchen installer](showcase-windows.md). The Start shortcut uses the DPAPI-protected installation. Personal actions require staff sign-in on the shared workstation.
3. Credentials expire after 90 days. To rotate, issue a replacement with a distinct label, use the installer action `ReplaceDeviceCredential`, verify that the replacement works, then revoke the previous record. For a lost or compromised workstation, revoke immediately in **Staff & Security**. Each subsequent request checks revocation, and the audit retains the workstation identity.

## Manager provisioning API v1

These operations require a Manager session cookie. Writes require the existing CSRF header. Issuance requires HTTPS. Responses use `Cache-Control: no-store`.

| Method and path under `/api/v1/admin` | Response |
| --- | --- |
| `GET /kitchen-workstations` | 200 with an array of workstation metadata. |
| `POST /kitchen-workstations` | Accepts `{"label":"Main kitchen display"}`. Returns 201 with `workstation` metadata and `bearerToken` once. Returns 400 for an invalid label or 409 if the restaurant is unprovisioned. |
| `DELETE /kitchen-workstations/{id}` | Returns 204 for successful or repeated revocation and 404 for an unknown identity. |

Metadata contains `id`, `label`, `issuedAt`, `expiresAt` and nullable `revokedAt`. PostgreSQL stores only a digest of each high-entropy credential. The provisioning audit records the Manager who issued and, if applicable, revoked it.
