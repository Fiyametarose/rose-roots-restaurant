# Two-PC Windows deployment and lecturer demonstration

The acceptance test requires two separate physical Windows PCs on the same private LAN. PC 1 runs Customer JavaFX. PC 2 runs Kitchen/Admin JavaFX, the shared Spring Boot backend and one PostgreSQL instance. Both clients use the same backend. Only Spring Boot has the application's database credentials. There is no inter-PC demonstration result yet.

## PC 2 setup for the backend and staff owners

1. Connect both PCs to a network that permits device-to-device traffic. Use a stable LAN address or a local DNS name for PC 2. Prefer a DHCP reservation. Avoid guest Wi-Fi with client isolation.
2. Bind the shared HTTPS listener to the LAN interface and enable WebSocket upgrades on the same HTTPS origin. Issue a TLS certificate whose SAN matches the hostname or IP used by PC 1. Trust the issuing CA in the JDK used by both JavaFX launchers. Keep hostname verification enabled. Provision the Customer terminal and authorize Kitchen and Admin separately.
3. In an elevated PowerShell on PC 2, add an inbound rule for the actual HTTPS listener port and PC 1's address. Example addresses below are placeholders. Confirm the interface uses the Private profile on the trusted demo LAN. Use the actual listener port. This rule also carries WSS. [Microsoft Windows Firewall documentation](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule?view=win10-ps)

   ```powershell
   $customerIp = '192.0.2.10'
   $httpsPort = 8443
   Get-NetConnectionProfile
   New-NetFirewallRule -DisplayName 'Rose Roots demo HTTPS and WSS' -Direction Inbound `
       -Action Allow -Protocol TCP -LocalPort $httpsPort -RemoteAddress $customerIp -Profile Private
   ```

4. Keep PostgreSQL bound to PC 2 loopback and its port closed to the LAN. Give Spring Boot a dedicated database role and restrict `pg_hba.conf` to the required local role/database with password authentication. Launch PostgreSQL and Spring Boot, then launch Kitchen/Admin independently using their release instructions. [PostgreSQL listener settings](https://www.postgresql.org/docs/current/runtime-config-connection.html) · [PostgreSQL client authentication](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html)

## PC 1 setup for Customer

Build the Windows distribution using Java 21. Copy the complete `build/install/rose-roots-customer` directory to PC 1 and start it independently with Java 21 available. Set the address in the launch shell. Do not include credentials or the REST prefix in the URL.

```powershell
# Build on a Windows development machine.
.\gradlew.bat build installDist

# Run on PC 1 from the installed distribution folder.
$env:ROSE_ROOTS_BACKEND_URL = 'https://restaurant-pc.example:8443'
Test-NetConnection restaurant-pc.example -Port 8443
Test-NetConnection restaurant-pc.example -Port 5432
.\bin\rose-roots-customer.bat
```

The listener probe should succeed and the PostgreSQL port probe should fail. A TCP probe does not verify TLS, REST or WebSocket authorization. The configured HTTPS origin becomes the shared `/api/v1/` REST base and `/ws/v1` WSS endpoint. There is no default network origin. Use an address and port issued by the team.

## Integration plan and readiness

| Owner | Required before the physical demonstration |
| --- | --- |
| Customer | Connect authoritative device/session bootstrap and catalog to the existing order coordinator. Bind the LAN status watcher to the active session's order view. Keep Place Order disabled until compatibility and authentication succeed. |
| Shared backend | Deploy one compatible API with TLS, terminal credentials, table/pickup assignment, quotes, durable idempotent acceptance, session history and bill. Establish authenticated STOMP 1.2 and publish committed events. Agree on subscription acknowledgement before the initial REST read. |
| Kitchen/Admin | Release the staff launcher with backend configuration. Verify live ticket receipt, kitchen actions and device/table configuration against the shared server. |
| Demonstration team | Provision two real PCs and run the checks below. Capture evidence from both computers. |

The Customer transport prepares authenticated REST and WSS for the same origin. The watcher subscribes before requesting paginated order history and the bill. It treats current-session events as refresh signals, drops completions from replaced connections and retries with bounded backoff. Periodic REST reconciliation and WebSocket ping/pong detect stale connectivity. The initial synchronization currently expects a STOMP subscription receipt. Confirm receipt support or agree on another subscription-ready signal with the backend owner before wiring it into the live UI. The current launcher reads the address but deliberately leaves Place Order disabled pending the authoritative bootstrap. The transport and reconnect coordinator are prepared and are not a claim of a working cross-PC deployment.

## Lecturer acceptance checklist

1. Record the two physical PC names, build revisions and configured PC 2 origin. Run the port checks from PC 1 and record the result without exposing credentials.
2. Launch both Windows applications independently. Resolve the provisioned table or server-issued pickup reference on PC 1.
3. Submit a real order from PC 1 and record the server-issued order ID. Match that exact ID to the ticket on PC 2 and the backend's persisted order. Verify that retrying an ambiguous acceptance does not add a second ticket.
4. Move the ticket from Received to Preparing on PC 2. Confirm that PC 1 displays the change without manual refresh. Record the WebSocket update and timestamps to distinguish it from periodic REST reconciliation.
5. Disconnect PC 1 from the LAN. Change the same ticket on PC 2 while PC 1 is offline. Reconnect PC 1 and verify that history and bill recover the latest server state and the same order ID. Then make one more Kitchen update and confirm live delivery resumed. Verify the cart survives a disconnected submission.
6. Record pass/fail, observed timestamps and screenshot paths for each step. Synthetic transport tests, one-host testing and demo sessions do not count as physical acceptance.
