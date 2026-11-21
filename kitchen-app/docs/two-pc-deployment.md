# Two-PC Windows deployment and demonstration

This runbook defines the integrated acceptance test. The current Kitchen build uses local services. Run the physical demonstration after the shared server, credentials and client adapters are available.

| Computer | Processes |
| --- | --- |
| PC 1 | Customer JavaFX only |
| PC 2 | Kitchen JavaFX with protected Admin mode, the shared Spring Boot process, and PostgreSQL |

## Implementation and ownership

- **Kitchen** will consume the shared staff REST API and private STOMP order events. The launcher will accept one configured HTTPS server origin through the agreed deployment setting. The network adapter will resolve REST under `/api/v1` and the draft STOMP transport at `/ws/v1` on the same origin. Preserve the local provider as an explicit standalone mode. In connected mode, keep the last authoritative snapshot for display during an outage, show its stale/offline status, and disable writes while authority is unavailable. After reconnect, subscribe and buffer, reload the complete paginated snapshot, then reconcile by event ID and order version. After an ambiguous write, reload the ticket before offering a retry. Never advance an item in the local provider as a fallback for a failed remote write.
- **Customer** owns its independent launcher, configured base URL with no localhost requirement, terminal credential, order idempotency key and Customer-side STOMP/status recovery. PC 1 must use PC 2's LAN origin for both protocols. A retry of an ambiguous submission must reuse the same key and request under the agreed ordering contract.
- **Admin/infrastructure** owns the single server, PostgreSQL, deployment configuration, certificate and credentials. Coordinate the HTTPS listener, private STOMP destinations, post-commit events, authorization and integrated Gradle packaging before freezing this runbook. The existing Admin server defaults to loopback via `ROSE_SERVER_ADDRESS`; for this test the shared HTTPS listener must be reachable on PC 2's LAN interface. That setting alone does not configure TLS.

## Windows setup

1. Put both physical PCs on the same authorized LAN with peer-to-peer traffic allowed. Run `ipconfig` on each and record the active adapter addresses. Choose a stable PC 2 hostname that resolves from PC 1, and use a certificate whose name matches that hostname and is trusted by both app runtimes. If using an IP address as the URL, the certificate must include that IP. Retain TLS validation for HTTPS and WSS.
2. On PC 2, configure PostgreSQL to listen only on loopback, for example `listen_addresses = 'localhost'`. Restrict `pg_hba.conf` to the Spring Boot database and runtime role on loopback, with password authentication. Keep pre-existing authorized maintenance access as required. Give the database credentials only to Spring Boot and keep the PostgreSQL port closed to the LAN. [PostgreSQL client authentication](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html)
3. On PC 2, open just the shared HTTPS listener for PC 1. This example uses an authorized Private network, example IPs, and a chosen TLS port. Substitute the actual addresses and port, confirm the active profile with `Get-NetConnectionProfile`, and run in an elevated PowerShell. REST and WSS use the same TCP port. [Microsoft Windows Firewall command documentation](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule?view=win10-ps)

   ```powershell
   $staffPcAddress = '192.168.1.25'
   $customerPcAddress = '192.168.1.24'
   $roseRootsTlsPort = 8443
   New-NetFirewallRule -Name 'RoseRoots-LAN-Demo' -DisplayName 'Rose & Roots HTTPS and WSS' `
     -Direction Inbound -Action Allow -Protocol TCP -Profile Private `
     -LocalAddress $staffPcAddress -RemoteAddress $customerPcAddress -LocalPort $roseRootsTlsPort
   ```

4. On PC 1, run `Test-NetConnection <PC2-hostname> -Port <TLS-port>` and expect TCP success. On the same PC, test `Test-NetConnection <PC2-hostname> -Port <PostgreSQL-port>` and expect failure. Use authenticated application requests to verify TLS, REST and STOMP. TCP reachability alone checks only the listener and route.
5. On PC 2, start PostgreSQL, then the integrated server using `.\gradlew.bat :server:bootRun`, then Kitchen/Admin in a separate terminal using `.\gradlew.bat :kitchen-app:run`. These are the module commands in the Admin integration worktree. On PC 1, launch only the Customer distribution built from the integrated revision. Customer is currently the root application, whose source-tree launch command is `.\gradlew.bat run`. Before the demonstration, record the final application launcher commands and approved configuration keys on both PCs. Set PC 1's Customer origin to PC 2's address, for example `https://staff-pc.example.test:8443` with the actual hostname and port, and provision each client through its approved credential mechanism.

## Acceptance tests and lecturer walkthrough

| Test | Action and pass condition |
| --- | --- |
| Independent launch and segregation | Launch each desktop app on its assigned physical PC. Confirm PC 1 reaches the shared application port while its PostgreSQL-port probe fails. PC 2 runs one server and one database. |
| Real submission | On PC 1, quote and submit one eligible item. Record the accepted order ID. The same ID appears on PC 2 without pressing Refresh. Confirm the server accepted one order. |
| Real-time return | On PC 2, start the item and then mark it ready. PC 1 displays Preparing and Ready in order through authenticated notifications and authoritative reads, without a manual reload. The ticket is Ready and eligible for handoff. Served and Collected require separate explicit operations. |
| Physical interruption | Disable the network adapter or disconnect only PC 1. While it is offline, make a valid transition on PC 2. Restore PC 1's network and verify it shows the latest persisted state with the original order ID, no duplicate and no manual restart. Verify the connection label reflects the outage. |
| Retry and event replay | In integration tests, inject a lost acceptance response, duplicate/out-of-order notifications and an ambiguous Kitchen write. Confirm one Customer order per idempotency key and authoritative reconciliation without duplicate transitions. During the physical rehearsal, record the observed disconnect/reconnect and resulting order state. |
| Authorization and isolation | Test rejection of unauthorized or wrong-scope staff requests and subscriptions, including Rush under Kitchen Staff credentials. Verify PC 1 has no database credentials. |

Capture both physical screens showing the same order ID, timestamps for interaction and recovery, and the server-side version sequence or sanitized request/event evidence. Keep credentials and private customer notes out of the demonstration record. Keep the two-PC cases pending until the physical run passes. Local UI tests and single-PC integration tests are preparation for that run.
