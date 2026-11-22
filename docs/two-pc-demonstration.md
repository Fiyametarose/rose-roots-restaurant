# Two-PC Windows demonstration

For the integrated showcase use the [Windows showcase setup and startup guide](showcase-windows.md). The plan below predates the integrated REST polling workflows.

| Computer | Processes | Network access |
| --- | --- | --- |
| PC 1 | Customer JavaFX | Outbound HTTPS and WSS to PC 2 |
| PC 2 | Kitchen JavaFX with protected Admin, one Spring Boot backend, one PostgreSQL service | Spring Boot listens on the LAN. PostgreSQL listens on loopback. |

Both JavaFX applications use the same Spring Boot origin. Admin can already use
that origin through `ROSE_BACKEND_URL`. The Customer and Kitchen order clients
and the shared order/event endpoints must be integrated before the order demo.

For persistent Menu and Category Management, enable `catalog,lan` and set
the datasource variables in the [Admin Phase 2A guide](admin-phase-2a.md).
Phase 2A publishes menu reads from the same backend origin for Customer integration.

## Implementation and contract handoff

| Owner | Work required for the demo |
| --- | --- |
| Admin / shared deployment (this branch) | Provide the opt-in Spring Boot `lan` profile and shared Admin origin configuration. Test real TLS and Secure session cookies. Preserve the local development profile. |
| Customer owner | Read the configured origin in the integrated Customer launch path. Send real order submissions and authoritative order reads over REST. Subscribe to authorized order events, then reconcile with REST on reconnect. |
| Kitchen owner | Read the configured origin in the integrated Kitchen launch path. Load real tickets, submit status transitions over REST, subscribe to ticket changes, and reconcile after reconnect. |
| Shared order, realtime and persistence owners | Serve REST and WebSocket from the same Spring Boot process. Persist orders and transitions in the existing shared PostgreSQL schema. Publish events after commit and enforce terminal/session and staff permissions. |

Before integration, agree on the exact REST paths under `/api/v1`, the
WebSocket path and subscriptions, authentication, order identifiers, status
values, and the latest-state reads available to each client. Use `https://` for
REST and `wss://` on the same host and port for WebSocket. Coordinate
`ROSE_BACKEND_URL` as the client origin setting. The Customer LAN launch needs
an explicit origin without a localhost fallback. No order route or topic in
this guide is presented as an implemented contract.

On reconnect, clients should reauthenticate and re-subscribe before fetching
the current authorized state. The contract needs a version or event identifier
and a rule for deduplicating events that race the snapshot. A submission with
an uncertain response must be reconciled or retried with the same agreed
idempotency identifier to avoid duplicate orders. The Customer, Kitchen and
shared API owners must agree on this behavior before enabling retries.

## Set up PC 2

1. Connect both Windows PCs to the same trusted LAN. Run `ipconfig` on each PC
   to identify the active adapter and IPv4 address. Reserve PC 2's address for
   the demonstration or use a stable DNS name. The examples below use
   `192.168.1.25` for PC 2 and `192.168.1.24` for PC 1. Replace both.
2. On PC 2, open an elevated PowerShell. Enable inbound TCP 8443 for PC 1 on
   the Private Windows firewall profile. Check that the active adapter is
   on a Private profile with `Get-NetConnectionProfile`.
   [Microsoft firewall reference](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule)

   ```powershell
   New-NetFirewallRule -Name 'RoseRootsDemo' -DisplayName 'Rose and Roots TLS demo' `
     -Direction Inbound -Action Allow -Profile Private -Protocol TCP `
     -LocalAddress 192.168.1.25 -LocalPort 8443 -RemoteAddress 192.168.1.24
   ```

3. On PC 2, start the single dedicated Rose & Roots database using the
   [database operations guide](database-environment.md). The published port
   is bound to Windows loopback only. Give the datasource credentials to the
   backend process. Leave the PostgreSQL host port closed to the LAN.
4. Use an existing certificate with a subject alternative name matching PC 2's
   LAN address or hostname. For a short lab demo, create a password-protected
   PKCS#12 key store outside the repository. The JDK prompts for the store
   password. Use the same password for the private key if prompted.

   ```powershell
   New-Item -ItemType Directory -Force C:\RoseRoots | Out-Null
   keytool -genkeypair -alias rose-lan -keyalg RSA -keysize 3072 -validity 30 `
     -dname 'CN=192.168.1.25' -ext 'SAN=IP:192.168.1.25' `
     -storetype PKCS12 -keystore C:\RoseRoots\server.p12
   keytool -exportcert -rfc -alias rose-lan -keystore C:\RoseRoots\server.p12 `
     -file C:\RoseRoots\rose-lan.cer
   ```

   Transfer only the public `rose-lan.cer` to PC 1. Verify its fingerprint
   before trusting it with `keytool -printcert -file C:\RoseRoots\rose-lan.cer`
   on both PCs. The private key remains on PC 2.
5. In a regular PowerShell window on PC 2, set the following variables.
   Enter the key-store password when prompted and configure the Manager
   account in the same shell using the [Phase 1 guide](admin-phase-1.md).
   [Spring Boot TLS settings](https://docs.spring.io/spring-boot/4.0/how-to/webserver.html#howto.webserver.configure-ssl)

   ```powershell
   $env:SPRING_PROFILES_ACTIVE = 'catalog,lan'
   $env:ROSE_SERVER_ADDRESS = '192.168.1.25'
   $env:ROSE_SERVER_KEY_STORE = 'file:///C:/RoseRoots/server.p12'
   $env:ROSE_SERVER_KEY_STORE_PASSWORD = [System.Net.NetworkCredential]::new(
       '', (Read-Host 'Server key-store password' -AsSecureString)).Password
   .\gradlew.bat :server:bootRun
   ```

## Launch the desktop clients independently

Build the Windows launchers from the integrated checkout on a Windows machine
with Java 21. Copy the respective distribution directory to each PC, keeping
its `bin` and `lib` directories together.

```powershell
.\gradlew.bat :installDist :kitchen-app:installDist
```

Use `build\install\rose-roots-customer` on PC 1 and
`kitchen-app\build\install\kitchen-app` on PC 2.

Use Java 21 on both PCs. For a lab certificate, import the verified public
certificate into a per-demo Java trust store on each PC.

```powershell
keytool -importcert -alias rose-lan -file C:\RoseRoots\rose-lan.cer `
  -storetype PKCS12 -keystore C:\RoseRoots\demo-trust.p12
```

In a fresh PowerShell on each client PC, use the same LAN origin and trust store.
For the public-certificate-only trust store use a demo password of letters
and digits, distinct from the server's private key-store password.
The packaged launchers read `JAVA_OPTS`. If Java already trusts the server's
certificate, omit the trust-store commands.

```powershell
$env:ROSE_BACKEND_URL = 'https://192.168.1.25:8443'
$trustPassword = [System.Net.NetworkCredential]::new(
    '', (Read-Host 'Demo trust-store password' -AsSecureString)).Password
$env:JAVA_OPTS = "-Djavax.net.ssl.trustStore=C:\RoseRoots\demo-trust.p12 -Djavax.net.ssl.trustStorePassword=$trustPassword"
```

On **PC 1**, launch Customer from its distribution directory with
`.\bin\rose-roots-customer.bat`. The current Customer launcher still uses local services.
Complete the Customer integration in the handoff table before using it for the
physical order demonstration.

On **PC 2**, launch Kitchen from its distribution directory with
`.\bin\kitchen-app.bat` and the client settings above. Clear any
older `ROSE_ADMIN_SERVER_URL` override in that shell or give it the same HTTPS
origin. Click **Manager sign-in** to verify the Admin path. The current Kitchen
board uses local tickets until its order client is integrated.

## Lecturer acceptance checks

Run these against the integrated system on both physical PCs. Record the
machines' addresses, observed order IDs, timestamps, and screenshots from
each screen. Local automated tests cover HTTPS and Admin cookies only.

| Check | Evidence |
| --- | --- |
| Confirm isolation | On PC 1, `Test-NetConnection 192.168.1.25 -Port 8443` succeeds and the same check on the PC 2 database host port (5434 by default) fails. Verify certificate trust on both clients. |
| Publish a catalog change | On PC 2, sign in to Admin and save a dish or price. From PC 1, fetch `GET /api/v1/menu` from the trusted HTTPS origin and confirm the updated dish ID and price. Once the Customer adapter is connected, verify the same change on its menu. |
| Publish restaurant branding | On PC 2, save a branding change approved for the demo. On PC 1, fetch `GET /api/v1/settings/public` and resolve its `logoReference` against the same trusted HTTPS origin. Repeat after restarting Spring Boot and compare the saved settings and logo. Once the client adapters are connected, verify that each client refreshes its branding without a rebuild. See [Phase 4A integration](admin-phase-4a.md#customer-and-kitchen-integration). |
| Submit a real order | On PC 1 submit an identifiable order. Record its server-issued ID and show the matching ticket arriving on PC 2. |
| Show live changes | Change the ticket status on PC 2. Show PC 1 reflecting the change without a manual refresh. |
| Interrupt and restore | Disconnect PC 1 from Wi-Fi or Ethernet while PC 2 continues operating. Change the ticket on PC 2. Reconnect PC 1 and verify it resubscribes, loads the missed status from REST, and receives another new update. |
| Check duplicates and persistence | Repeat with a submission whose response is interrupted. Verify exactly one order exists and both clients converge on the same ID and current status. Restart a client and verify the order is restored from the same backend. |

Physical two-PC order and reconnect verification is pending integration and
access to both machines. When the demo is finished, remove the inbound rule
from elevated PowerShell with `Remove-NetFirewallRule -Name 'RoseRootsDemo'`.
