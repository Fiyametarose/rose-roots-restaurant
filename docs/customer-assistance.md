# Customer assistance integration proposal

The Assistance control opens a themed, owned dialog from the menu, dish details, cart, order confirmation and order tracking. The current screen and its cart, orders and session stay in place. Drinking water is a complimentary service request. Bottled water remains a catalog item. Allergy review remains part of the order workflow, and customers can view their bill independently of Request Bill.

## Contract status

Customer v1 OpenAPI and the fetched Kitchen and Admin integration branches have no assistance wire contract. Both staff branch tips were at baseline `502f704` when inspected. The Java adapter delegates to an authenticated shared backend client. Its DTOs are proposals for coordination. There are no new HTTP paths, JSON fields or WebSocket destinations in the shared contract.

- The backend resolves the authenticated device, current authorized session, and table or pickup reference. Client context is for scoping and verifying responses. It does not authorize arbitrary routing.
- Agree on the seven request kinds, optional Other text, authoritative submission timestamp and request ID, and representation of Submitted, Acknowledged, In Progress and Completed. Submitted means the backend accepted the request. Acknowledged means staff acted on it.
- Agree on an idempotent submission operation, the key scope and replay retention, a session-scoped request snapshot, and a private event that triggers a REST reload. The adapter should subscribe before reloading and discard results from replaced connections. Replaying the same key must preserve the original request ID.
- The client keeps one uncertain submission per visit in memory. Reopening the dialog retains its original key, request type, message and timestamp. A newer request of the same type is available after completion. Requests of other types are available once the current submission is resolved. Confirm which failures are definitive, and agree on recovery after an application restart.
- Staff delivery, authorization, channel names and terminal credentials require agreement and live verification on the shared Spring Boot backend. The current launcher reports unavailability. The screenshots with acknowledgements and progress are explicitly labeled fixtures.

See the [two-PC demonstration plan](two-pc-windows-demo.md) for deployment. Once the contract is available, include an Assistance request and its observed status transitions in the physical LAN acceptance run.
