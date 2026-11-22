# Rose & Roots Kitchen

The Kitchen client is a Java 21 and JavaFX kitchen board using an in-memory `KitchenService`. All updates reset on restart. The board opens directly and supports ticket and individual item preparation actions, station filters, Order Details, Priority Queue, and Service & Pickup Handoff.

From the repository root run the staff application with the shared wrapper.

```powershell
.\gradlew.bat :kitchen-app:build
.\gradlew.bat :kitchen-app:run
```

On macOS or Linux use `./gradlew` in place of `.\gradlew.bat`. The **Manager sign-in** button opens the Admin workspace in both Kitchen modes. See the [Admin Phase 1 guide](../docs/admin-phase-1.md) for server configuration.

## Local Kitchen workflows

All Orders shows whole-order status. A station filter shows only matching items and derives the column and counts from that station's work. The order summary always counts whole active tickets. Lane counts reflect the selected filter. Handoff requires every item on the ticket to be ready and groups dine-in and takeaway orders. Readiness does not confirm packing, serving or collection. Future SERVED and COLLECTED confirmations require server-authorized staff actions. Elapsed time is measured from submission. The current delay target measures total elapsed time since submission, including time spent preparing. An unfinished order becomes delayed at the target configured on that ticket.

Open **Details** on any Live Orders ticket to see all its stations and update individual items. Returning to Live Orders preserves its selected station. The Priority Queue sorts waiting work by Rush, Delayed and Normal, then by oldest submission. In-progress tickets stay in their own oldest-first group even if their Rush setting changes.

The priority panel starts with a local Kitchen Staff profile. Select the local Supervisor or Manager profile and provide a reason to apply or remove Rush. The service validates the local role and records the order ID, action, reason, staff ID and timestamp in memory. Selecting a profile updates the current local identity. Rush requests use that identity without accepting a caller-supplied staff ID. In production Spring Security must resolve the actual staff identity and authorize each change on the shared server. The app reports **Local mode — Server not connected**. Local Rush profiles apply to the Kitchen demo only. Manager sign-in uses the Admin server.

**Stations** groups items by assigned station and counts item lines by state. One ticket can appear in multiple stations. Station actions share the same ticket service as Live Orders and Order Details, so whole-order readiness and Handoff still require every item to be ready.

**Assistance** uses a separate local request service. Incoming request IDs are deduplicated. Acknowledging a pending request and resolving an acknowledged request each record the current local staff ID and timestamp. Assistance actions create no order or billing records. The local inbox and action history reset on restart.

**Reviews** is a read-only Kitchen view of food-related local reviews. Its average and count reflect both selected filters. The review records use session identifiers and topic tags so the shared Customer and Admin workflows can be mapped later.

**History & Sync** reads from the same ticket repository as Live Orders. Completing all preparation records Ready and keeps the order eligible for Handoff. Served, Collected and Cancelled outcomes on this screen are seeded local history. This phase adds no service-confirmation workflow. Records reset on restart. The local connection provider never reports connected or sends simulated synchronization notifications.


See [Two-PC Windows deployment and demonstration](docs/two-pc-deployment.md) for the integration plan, Windows setup and physical acceptance tests.

## Connected REST mode

`ROSE_KITCHEN_MODE=local` selects the original in-memory provider and is the default. Set `ROSE_KITCHEN_MODE=connected` to require authenticated REST. Set `ROSE_BACKEND_URL` to the approved HTTPS origin (host and optional port, with no API path). `ROSE_ROOTS_BACKEND_URL`, used by Customer, is a compatibility fallback when the shared setting is blank or absent. Kitchen does not read the legacy Admin-only URL setting. Configure `ROSE_KITCHEN_STAFF_TOKEN` in the launch environment with an actual server-issued staff bearer credential. Keep it out of source, command transcripts and screenshots. The credential provider can later be wired to the shared authentication session. Connected mode has no local identity selector.

```powershell
$env:ROSE_KITCHEN_MODE = 'connected'
$env:ROSE_BACKEND_URL = 'https://staff-pc.example.test:8443'
# Supply ROSE_KITCHEN_STAFF_TOKEN via the approved staff credential provisioning.
.\gradlew.bat :kitchen-app:run
```

Use the real hostname on the server certificate and trust its issuing CA in the Java runtime. Connected mode fetches immediately, then reads every three seconds after the previous operation finishes. Repeated failures back off to 30 seconds. **Refresh tickets** can request an immediate read when no operation is running. The client applies a snapshot only after reading every page and rejects older versions. A network or response failure keeps the last complete snapshot and disables preparation writes. An authentication or authorization rejection clears cached tickets.

If a background read is in progress when a staff member clicks an item action, the client checks the displayed item version against the completed read before sending the PATCH. A changed or held item requires a new action. A conflicted or uncertain write triggers a fresh read. The client never retries a PATCH automatically and stops synchronization when Kitchen closes.

The board displays the authoritative order UUID. READY stays active until staff confirms served or collected on the Handoff screen. Connected Assistance polls for shared requests, and Reviews reads the shared food and preparation projection. History & Sync reads persisted READY, SERVED, COLLECTED, and CANCELLED orders. Open the screen or select Refresh history to fetch a fresh snapshot. Station assignments and Rush changes still require backend support. The local demonstration providers remain available in local mode.

The current Spring Boot transaction writes ordering outbox rows alongside committed order versions. The next STOMP slice needs agreement on credential scope, subscription readiness, reconnect behavior and cursor recovery. Delivery can use committed outbox rows to invalidate the active ticket view, which continues to reconcile through authoritative REST reads.

See [Chef provisioning and Assistance](../docs/ordering-draft-2-handoff.md) and the [Handoff and Reviews contract](../docs/reviews-contract-proposal.md).
