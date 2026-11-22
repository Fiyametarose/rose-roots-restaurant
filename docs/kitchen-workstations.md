# Shared Kitchen workstation access

Ordering API 1.0.0-draft.4 adds workstation bearer authentication to three existing operations under `/api/v1`. Customer clients keep the same endpoints, payloads, and statuses.

| Method and path | Workstation access |
| --- | --- |
| `GET /kitchen/orders` | Read the restaurant's active tickets, preparation states, stations and allergy information. |
| `GET /kitchen/assistance` | Read the restaurant's Assistance queue. |
| `PATCH /kitchen/assistance/{id}` | Advance one step from SUBMITTED to ACKNOWLEDGED, IN_PROGRESS, then COMPLETED. Supply the displayed `expectedVersion` with `status`. |

The existing seven request types, pagination and Customer session authorization remain in effect. Stale versions return `409 VERSION_CONFLICT`. An invalid step with the current version returns `409 INVALID_TRANSITION`. Reload the queue after a conflict or an uncertain response.

A workstation credential never has individual Kitchen, allergy review, Supervisor, Customer terminal, or Manager authority. It cannot write preparation, priority, handoff, payments or staff data. It cannot access Kitchen history or reviews. Unknown, expired and revoked credentials return 401. Authorized credentials used outside their scope return 403 on the ordering API. Assistance audit rows retain the credential UUID and timestamp. For shared access that UUID resolves to the workstation record, including after revocation.

## Provision and rotate

1. Connect to the trusted HTTPS server. In Manager sign-in, open **Staff & Security** and issue a credential under **Shared Kitchen workstations**.
2. Copy the new credential once. Use a protected secret delivery channel, disable clipboard history and cross-device sync during transfer, and clear the clipboard afterward. Store the credential as the only line of a file **outside the repository**, readable only by the workstation OS account and authorized administrators. Keep the file out of synced folders and backups that are shared with other users.
3. Launch Kitchen with `ROSE_KITCHEN_MODE=connected`, the trusted `ROSE_BACKEND_URL`, and `ROSE_KITCHEN_WORKSTATION_TOKEN_FILE` set to the absolute path of the protected file. The workstation skips personal sign-in and retains its polling and reconnect behavior. An unavailable or invalid file leaves it disconnected. The configured file takes precedence over `ROSE_KITCHEN_STAFF_TOKEN`, with no fallback. Individual deployments can continue to use the existing staff configuration without the workstation file setting.
4. Credentials expire after 90 days. To rotate a working credential, issue a replacement with a distinct label, install it, and revoke the previous record. For a lost or compromised workstation, revoke immediately in **Staff & Security**. Each subsequent request checks revocation. Revoking the credential preserves its audit identity.

## Manager provisioning API v1

These operations require a Manager session cookie. Writes require the existing CSRF header. Issuance requires HTTPS. Responses use `Cache-Control: no-store`.

| Method and path under `/api/v1/admin` | Response |
| --- | --- |
| `GET /kitchen-workstations` | 200 with an array of workstation metadata. |
| `POST /kitchen-workstations` | Accepts `{"label":"Main kitchen display"}`. Returns 201 with `workstation` metadata and `bearerToken` once. Returns 400 for an invalid label or 409 if the restaurant is unprovisioned. |
| `DELETE /kitchen-workstations/{id}` | Returns 204 for successful or repeated revocation and 404 for an unknown identity. |

Metadata contains `id`, `label`, `issuedAt`, `expiresAt` and nullable `revokedAt`. PostgreSQL stores only a digest of each high-entropy credential. The provisioning audit records the Manager who issued and, if applicable, revoked it.
