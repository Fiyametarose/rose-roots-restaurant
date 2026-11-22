# Individual Kitchen sign-in and preparation

Deploy Flyway V19 and the paired Kitchen distribution together. Customer ordering and Assistance payloads are unchanged. The ordering contract amendment is version 1.0.0-draft.5.

In Manager **Staff & Security**, create a Chef or Supervisor account with a password over HTTPS. Use **Set staff password** for an existing account. Setting or resetting a password retires its previous individual bearer token and closes its active personal sessions. Keep the shared workstation credential configured separately. In **Kitchen Configuration**, assign each Chef to the appropriate active stations, set their availability, and configure menu-item prep profiles so accepted items have a station.

On the shared board, select **Staff sign-in** and enter the personal username and password. The session is in memory and expires after 30 minutes. **Lock / switch staff** immediately removes personal access locally and requests server sign-out. If the network is unavailable during lock, the server session still expires at its original deadline. The board and Assistance continue using the workstation credential.

Open an order in **Details** and select **Claim Item**. Claiming changes responsibility for one item only. Release is available to its Chef before prep begins. A Supervisor can reassign a queued or preparing item by choosing an eligible Chef and entering a reason. If a Chef's availability or station assignment ends during preparation, a Supervisor can reassign the item to an eligible Chef.

Only the current Chef may mark their item PREPARING and READY. An allergy hold blocks preparation even after a claim or Rush. An active Chef can review allergies after personal sign-in. **Preparation history** in order details and **History & Sync** shows the actor, owner and status changes, version, timestamp and reassignment reason. Items accepted before V19 have no inferred owner or fabricated history.

## Ordering API changes

| Method and path under `/api/v1/kitchen` | Authentication and behavior |
| --- | --- |
| `POST /staff-session` | HTTPS and the launch's workstation or legacy staff bearer. Accepts username and password. Returns the personal bearer once. |
| `DELETE /staff-session` | Revokes the supplied personal bearer. |
| `POST /items/{id}/ownership` | Personal Chef for CLAIMED or RELEASED. Personal Supervisor for REASSIGNED with chefId and reason. All require expectedVersion. |
| `PATCH /items/{id}/status` | Personal Chef who currently owns the item and is available for its station. Existing payload and allergy requirements. |
| `GET /items/{id}/eligible-chefs` | Personal Supervisor. Lists available, assigned alternatives. |
| `GET /items/{id}/preparation-history` | Individual Kitchen access. Returns history ordered by item version. |

Kitchen ticket projections may include `itemChefs` keyed by item UUID. Customer order projections omit staff identity and history. Stale versions return `409 VERSION_CONFLICT`. A currently claimed item returns `409 ITEM_ALREADY_CLAIMED` for a current-version competing claim. Ineligible station or availability returns `409 CHEF_NOT_AVAILABLE`. An allergy hold continues to return `409 REVIEW_REQUIRED`. Authentication errors and conflicts do not change preparation state.

Manager `PATCH /api/v1/admin/staff/{id}` accepts optional `kitchenPassword` for existing Chef and Supervisor accounts. This operation uses the existing version, cookie and CSRF checks, and requires HTTPS when changing the password.
