# Staff account lifecycle

In **Staff & Security**, create a Manager, Chef, Supervisor or Waiter by entering the username, role and personal password together over HTTPS. Staff passwords require at least 12 characters and at most 72 UTF-8 bytes. New accounts use personal sign-in. Shared workstation provisioning continues to issue separate workstation credentials.

- **Active** accounts can use their role's functions after signing in. A migrated account that has no personal staff password can be provisioned with **Set staff password**.
- **Deactivated** accounts have their sessions and previous bearer access revoked. To reactivate, choose **Activate with new password**. The last password cannot be reused.
- **Archived** accounts appear under **Archived accounts**. **Restore** moves them to Deactivated and retains all historical identity and audit references. Activation is a separate step.

Managers cannot deactivate or archive themselves. The configured bootstrap Manager is managed outside Staff & Security, and the server guards access to the last usable Manager. Password replacement invalidates prior personal sessions, including previous Manager browser sessions.

All active Chefs can review allergies with a personal Kitchen session. Workstation credentials, legacy bearer credentials and other roles cannot submit a decision. The server still enforces the held item's version, review state, ingredient risk and preparation restrictions. Ordering OpenAPI 1.0.0-draft.7 records the new authentication requirement. Customer contracts are unchanged.

## Manager API v1

Manager cookie and CSRF protection remain in effect. Creation and password changes require HTTPS.

| Operation | Fields and result |
| --- | --- |
| `GET /api/v1/admin/staff` | Returns an array of account metadata containing `id`, `username`, `role`, `state`, `needsPasswordSetup` and `version`. |
| `POST /api/v1/admin/staff` | Accepts `username`, `role` and `password`. Returns the created `account`. |
| `PATCH /api/v1/admin/staff/{id}` | Accepts `expectedVersion` plus a target `state`, a new `password`, or both. Returns the updated account. Activate a deactivated account with `state: "ACTIVE"` and a new password. |

Stale requests return 409. Flyway V21 adds archival and credential state to the existing accounts and adds previous/new state to the existing lifecycle audit, with timestamp and Manager identity. Prior audit records and staff identifiers remain intact.
