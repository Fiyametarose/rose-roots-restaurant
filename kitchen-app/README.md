# Rose & Roots Kitchen

The Kitchen client is a Java 21 and JavaFX kitchen board using an in-memory `KitchenService`. All updates reset on restart. The board opens directly and supports ticket and individual item preparation actions, station filters, Order Details, Priority Queue, and Service & Pickup Handoff.

From the repository root run:

```powershell
.\gradlew.bat -p kitchen-app clean build
.\gradlew.bat -p kitchen-app run
```

On macOS or Linux use `./gradlew -p kitchen-app`. The module-local settings allow the existing wrapper to launch Kitchen alongside the Customer baseline. Member 3 can register `kitchen-app` from the shared root settings during the coordinated multi-project migration.

All Orders shows whole-order status. A station filter shows only matching items and derives the column and counts from that station's work. The order summary always counts whole active tickets. Lane counts reflect the selected filter. Handoff requires every item on the ticket to be ready and groups dine-in and takeaway orders. Readiness does not confirm packing, serving or collection. Future SERVED and COLLECTED confirmations require server-authorized staff actions. Elapsed time is measured from submission. The current delay target measures total elapsed time since submission, including time spent preparing. An unfinished order becomes delayed at the target configured on that ticket.

Open **Details** on any Live Orders ticket to see all its stations and update individual items. Returning to Live Orders preserves its selected station. The Priority Queue sorts waiting work by Rush, Delayed and Normal, then by oldest submission. In-progress tickets stay in their own oldest-first group even if their Rush setting changes.

The priority panel starts with a local Kitchen Staff profile. Select the local Supervisor or Manager profile and provide a reason to apply or remove Rush. The service validates the local role and records the order ID, action, reason, staff ID and timestamp in memory. Selecting a profile updates the current local identity. Rush requests use that identity without accepting a caller-supplied staff ID. In production Spring Security must resolve the actual staff identity and authorize each change on the shared server. The app reports **Local mode — Server not connected**. Service confirmation, Admin review management and network integration remain pending.

**Stations** groups items by assigned station and counts item lines by state. One ticket can appear in multiple stations. Station actions share the same ticket service as Live Orders and Order Details, so whole-order readiness and Handoff still require every item to be ready.

**Assistance** uses a separate local request service. Incoming request IDs are deduplicated. Acknowledging a pending request and resolving an acknowledged request each record the current local staff ID and timestamp. Assistance actions create no order or billing records. The local inbox and action history reset on restart.

**Reviews** is a read-only Kitchen view of food-related local reviews. Its average and count reflect both selected filters. The review records use session identifiers and topic tags so the shared Customer and Admin workflows can be mapped later.

**History & Sync** reads from the same ticket repository as Live Orders. Completing all preparation records Ready and keeps the order eligible for Handoff. Served, Collected and Cancelled outcomes on this screen are seeded local history. This phase adds no service-confirmation workflow. Records reset on restart. The local connection provider never reports connected or sends simulated synchronization notifications.

Before integration, confirm DTO and error schemas, authoritative delay policy and Rush metadata, and the WebSocket subscription and reconnect contract with the backend and infrastructure owners.

See [Two-PC Windows deployment and demonstration](docs/two-pc-deployment.md) for the integration plan, Windows setup and physical acceptance tests.
