# Rose & Roots Kitchen

The Kitchen client is a Java 21 and JavaFX kitchen board using an in-memory `KitchenService`. All updates reset on restart. The board opens directly and supports ticket and individual item preparation actions, station filters, Order Details, Priority Queue, and Service & Pickup Handoff.

From the repository root run the Kitchen module with the shared wrapper.

```powershell
.\gradlew.bat :kitchen-app:build
.\gradlew.bat :kitchen-app:run
```

On macOS or Linux use `./gradlew` in place of `.\gradlew.bat`. For the
**Manager sign-in** entry point and server configuration, see the
[Admin Phase 1 guide](../docs/admin-phase-1.md).

All Orders shows whole-order status. A station filter shows only matching items and derives the column and counts from that station's work. The order summary always counts whole active tickets. Lane counts reflect the selected filter. Handoff requires every item on the ticket to be ready and groups dine-in and takeaway orders. Readiness does not confirm packing, serving or collection. Future SERVED and COLLECTED confirmations require server-authorized staff actions. Elapsed time is measured from submission. The current delay target measures total elapsed time since submission, including time spent preparing. An unfinished order becomes delayed at the target configured on that ticket.

Open **Details** on any Live Orders ticket to see all its stations and update individual items. Returning to Live Orders preserves its selected station. The Priority Queue sorts waiting work by Rush, Delayed and Normal, then by oldest submission. In-progress tickets stay in their own oldest-first group even if their Rush setting changes.

The priority panel starts with a local Kitchen Staff profile. Select the local Supervisor or Manager profile and provide a reason to apply or remove Rush. The service validates the local role and records the order ID, action, reason, staff ID and timestamp in memory. Selecting a profile updates the current local identity. Rush requests use that identity without accepting a caller-supplied staff ID. In production Spring Security must resolve the actual staff identity and authorize each change on the shared server. The app reports **Local mode — Server not connected**. Service confirmation, assistance, reviews, history and network integration remain pending.

Before integration, confirm DTO and error schemas, authoritative delay policy and Rush metadata, and the WebSocket subscription and reconnect contract with the backend and infrastructure owners.
