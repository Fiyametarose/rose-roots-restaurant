# Windows showcase setup and startup

The showcase uses the packaged Windows JavaFX Customer and Kitchen apps, the existing Kitchen Manager workspace, one Spring Boot process and one dedicated PostgreSQL 17 database. Customer and Kitchen use HTTPS polling. This guide supersedes the older two-PC launch and legacy staff-token instructions for the showcase.

## Three-PC layout and release build

| PC | Install | Network access |
| --- | --- | --- |
| 1 | Customer distribution and named Customer shortcut | HTTPS to PC 3 |
| 2 | Kitchen distribution, shared-workstation shortcut and Manager workspace | HTTPS to PC 3 |
| 3 | Server boot JAR, private PKCS12 key store and PostgreSQL 17 | Inbound HTTPS from PCs 1 and 2. PostgreSQL listens on loopback only. |

On the Windows build machine, select a Java 21 JDK and check `java -version` and `git rev-parse HEAD`. For a regression build, point `ROSE_TEST_DB_URL`, `ROSE_TEST_DB_USERNAME` and `ROSE_TEST_DB_PASSWORD` at a fresh disposable PostgreSQL 17 database before invoking Gradle. Tests can reset the database schema. Keep the test credentials in the build process environment.

```powershell
.\gradlew.bat clean build :installDist :kitchen-app:installDist :server:bootJar
```

Copy `build\install\rose-roots-customer` to PC 1, `kitchen-app\build\install\kitchen-app` to PC 2, and `server\build\libs\server.jar` to PC 3. Copy `scripts\showcase.ps1` to each PC and `compose.yaml` to PC 3 if using Docker. Keep these files at stable local paths. Record the commit SHA and use `Get-FileHash -Algorithm SHA256` on the boot JAR and the two application JARs to check the copies. The server JAR contains Flyway V23.

## LAN and HTTPS preflight

Reserve PC 3's LAN address and choose one canonical origin, such as `https://rose-backend.example.test:8443`. Use a hostname that both clients actually resolve to PC 3. With an IP-based origin, the certificate must have that IP as an IP SAN. With a hostname origin, use a matching DNS SAN. The port belongs in the origin only. Synchronize the PCs' clocks.

Use a server certificate and chain trusted by both client JDKs. For a dedicated lab using a self-signed certificate, Windows PowerShell on PC 3 can generate and export it as follows. Run in a protected local directory and substitute the actual hostname and IP. The JDK prompts for the private store password.

```powershell
keytool -genkeypair -alias rose-lan -keyalg RSA -keysize 3072 -validity 90 -dname "CN=rose-backend.example.test" -ext "SAN=DNS:rose-backend.example.test,IP:192.168.50.30" -storetype PKCS12 -keystore .\rose-server.p12
keytool -exportcert -alias rose-lan -rfc -keystore .\rose-server.p12 -file .\rose-server.cer
keytool -printcert -file .\rose-server.cer
```

Transfer only the public certificate to PCs 1 and 2. Compare its SHA-256 fingerprint with PC 3 using an independent channel, then import it into a public-only Java trust store on each client. For an organizational CA, use the approved CA trust chain instead. Provide the store path and its separate password to the client installer. A browser or Windows certificate warning and Java's trust validation are separate checks.

```powershell
keytool -printcert -file .\rose-server.cer
keytool -importcert -alias rose-lan -file .\rose-server.cer -keystore .\rose-lan-trust.p12 -storetype PKCS12
keytool -list -keystore .\rose-lan-trust.p12 -storetype PKCS12
```

Confirm the trusted LAN uses the Private Windows network profile. At Backend install, enter PC 3's LAN IP for the bind address and `8443` for HTTPS. Run the following in elevated PowerShell on PC 3 with the actual addresses. Keep PostgreSQL and its host port outside the firewall allow rule.

```powershell
Get-NetConnectionProfile
New-NetFirewallRule -DisplayName 'Rose Roots showcase HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress 192.168.50.30 -LocalPort 8443 -RemoteAddress 192.168.50.11,192.168.50.12 -Profile Private
```

For an additional physical Customer device, add its reserved IP to the permitted client addresses. If the site uses managed networking or Wi-Fi client isolation, confirm routing and firewall access with its operator. For native PostgreSQL, set `listen_addresses` to loopback and restrict the dedicated database and application role to local connections through `pg_hba.conf`.

## First-time setup

1. Install Java 21 on each PC and complete the release build and LAN preflight above. Keep the packaged client directories together, including their `lib` directories. Keep the server key store on PC 3 outside the repository, distributions and synced folders.
2. On PC 3, run the installer from Windows PowerShell 5.1. For Docker, use Docker Desktop's Linux engine with the `desktop-linux` context and point the installer at the copied `compose.yaml`. It uses the `rose-roots-showcase` project, a separate volume and loopback port 55434. If using native PostgreSQL 17, supply a dedicated showcase database and role. Read the target details before typing **SHOWCASE**. Provision the restaurant records later in Manager.

   ```powershell
   .\scripts\showcase.ps1 -Action Install -Role Backend
   ```

   A new dedicated database needs a bootstrap Manager for first sign-in. Enter its username and either a new password or an existing BCrypt hash at the masked prompt. The installer stores the hash once. If the dedicated database already has a working Manager account, leave the bootstrap username blank. Launch **Rose Roots Showcase - Backend - Backend** from Start. Use the certified origin with the configured HTTPS port.
3. For initial provisioning, open the packaged Kitchen app with Admin pointed at the trusted HTTPS origin. If the credentialed Kitchen shortcut does not exist yet, use the application's **Manager sign-in** in local display mode. Set the Admin server address where prompted and configure Java trust for this initial launch. [Initial Admin HTTPS launch](#initial-admin-https-launch)
4. In **Staff & Security**, create one persistent Manager, two Chefs, one Supervisor and one Waiter, each with a personal password. If repeating setup, check **Current accounts** and **Archived accounts** before creating anything and reuse the named accounts. Keep the bootstrap credential for recovery when configured. Sign out and verify the persistent Manager login before continuing.
5. Under **Tables & Dining Sessions**, register at least five enabled tables, for example `01` through `05`. Register three uniquely labeled terminals and assign three different tables. Use each issued credential for that terminal's one-time installation. In **Staff & Security**, issue one shared Kitchen workstation credential. On repeat setup, reuse the existing assignments and installed credentials. Reissuing for an existing working installation is unnecessary.
6. On each device's intended Windows sign-in, install its launcher. Enter the trusted origin and its own credential at the masked prompt. Transfer an issued credential only through a protected channel during setup. On the same PC, clear the clipboard afterward and keep clipboard history and sync off during transfer.

   ```powershell
   .\scripts\showcase.ps1 -Action Install -Role Customer -Name Terminal1
   .\scripts\showcase.ps1 -Action Install -Role Kitchen
   ```

   Use each label's own credential for `Terminal2` and `Terminal3`. The installer stores the configuration in the Windows user's local application data and protects secrets using Windows DPAPI and file permissions. The optional Compose password file is readable only by the installation account and Windows SYSTEM so Docker can mount it. Secrets are excluded from the shortcuts and distributions. Running Install again with the same role and name preserves settings and credentials.
7. In **Kitchen Configuration**, assign the two Chefs to active stations and start their shifts through the end of the showcase. A fresh database has seeded categories. Use **Add Dish** to create any missing showcase dishes, and **Edit Dish** to check each dish's station, base duration, optional upper duration and option workload. Include a priced option and accurate allergen declarations for the acceptance exercises. Configure only the dedicated showcase database. Use real orders and confirm cash only after collection.

### Initial Admin HTTPS launch

In a one-time PowerShell session, open the packaged Kitchen launcher `kitchen-app.bat` in local display mode. Set `ROSE_BACKEND_URL` to the trusted HTTPS origin so Manager sign-in uses it. If Java needs the public-certificate-only trust store, pass its path and independent nonsecret password through the packaged launcher's `JAVA_OPTS`. The installed Kitchen shortcut handles these settings thereafter.

```powershell
$env:ROSE_KITCHEN_MODE = 'local'
$env:ROSE_BACKEND_URL = 'https://rose-backend.example.test:8443'
$env:ROSE_ADMIN_SERVER_URL = $env:ROSE_BACKEND_URL
$env:JAVA_OPTS = '-Djavax.net.ssl.trustStore="C:\RoseRoots\rose-lan-trust.p12" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword="<public-store-password>"'
.\bin\kitchen-app.bat
```

Replace the example values with the actual origin, public trust store path and its separate nonsecret password. If Java already trusts the server, omit the trust-store options. Close this shell after provisioning.

## Daily startup

1. Start Docker Desktop if the backend installation uses Compose. For native PostgreSQL, use `Get-Service postgresql*` to find the PostgreSQL 17 service, start it with `Start-Service -Name <service-name>` in elevated PowerShell, and check it with `pg_isready -h 127.0.0.1 -p <database-port>`. Open **Rose Roots Showcase - Backend - Backend** from Start and leave its console open.
2. Open the **Rose Roots Showcase** Kitchen and appropriate Customer shortcuts on their PCs. Each client uses its installed credential. The shared board and Assistance open without staff sign-in. Chefs, the Supervisor and Waiter sign in individually for their actions, and Manager signs in through the Manager workspace.
3. To move a terminal, finish handoff and settlement and close its visit in **Tables & Dining Sessions**, then save its new assignment. On Customer, return to Welcome and select Dine-in for the next visit. The app fetches the server assignment again using the same installed device credential. Table numbers do not grant access to another terminal's visits or receipts.
4. For an expired or revoked credential, follow the existing Manager provisioning process for a replacement and run `showcase.ps1 -Action ReplaceDeviceCredential -Role Customer -Name Terminal1` with its actual role and name. Use a different label for a replacement terminal. For a workstation rotation while the old credential is active, issue a distinct label and revoke the old one after validating the replacement.

Bring the PCs onto the same trusted LAN, allow the backend's HTTPS port through the server firewall for those clients, and keep PostgreSQL on loopback. Verify the certificate hostname and trust on each PC. This guide covers Windows JavaFX clients.

## Connection checks and recovery

1. On PC 3, start the Backend shortcut first. Wait for PostgreSQL readiness, Flyway completion through V23 or confirmation the schema is current, and Spring Boot's HTTPS listener. For Compose, run `docker --context desktop-linux ps --filter name=rose-roots-showcase-db` and check the healthy state and `127.0.0.1:55434` port mapping. Check `Get-NetTCPConnection -State Listen -LocalPort 8443,55434` on PC 3.
2. From PCs 1 and 2, run `Resolve-DnsName rose-backend.example.test` for a DNS origin and `Test-NetConnection rose-backend.example.test -Port 8443`. Substitute the live origin host. A port failure points to binding, routing or firewall. A successful port probe verifies TCP only.
3. With a verified public CA or self-signed lab certificate available to curl, run `curl.exe --fail --cacert .\rose-server.cer https://rose-backend.example.test:8443/api/v1/menu`. Expect HTTP 200. If the Windows curl build uses the Windows trust store instead, use the configured Java clients for the trust check. Keep hostname and certificate validation enabled.
4. Start the installed Customer and Kitchen shortcuts. Check Customer's assigned table and Kitchen's connected indicator, then sign in to Manager. Java PKIX failures indicate a trust-chain or trust-store configuration problem. Hostname errors indicate a SAN or origin mismatch. Once HTTPS works, investigate 401 as an expired, revoked or wrong credential, and 403 as an authorization or scope issue. Verify clients cannot reach PC 3's PostgreSQL host port with `Test-NetConnection`.
5. If a client loses its connection, restore the LAN and watch polling reconnect. With an uncertain payment, reopen Payment and Receipt to recover the attempt or verified receipt before taking any further cash. For receipt recovery after terminal replacement, independently verify the claimant and receiving installation in Manager before issuing a code. [Receipt recovery steps](payment-settlement-v1.md#verified-receipt-recovery)

## Physical acceptance checklist

Record the commit, the three physical PC identifiers, the HTTPS origin, the outcomes and sanitized visit, order or receipt references. Keep passwords, tokens and recovery codes out of evidence.

- [ ] Start each installed role on its assigned PC. Verify trusted HTTPS, Manager sign-in and PostgreSQL isolation. Record that Customer and Kitchen ran on separate physical machines from the backend.
- [ ] Place a priced Customer order with an edited option and verify automatic Kitchen discovery. Submit a second round and compare the combined bill.
- [ ] Sign in as two Chefs, claim different items and check that a competing claim fails. Start and finish preparation. Verify that direct allergen conflicts are rejected, held items block preparation even with Rush, and a Chef can approve or decline with an audit reason.
- [ ] Confirm READY appears on both clients before handoff. Sign in as Waiter and record SERVED for dine-in or COLLECTED for takeaway. Confirm Customer status and review eligibility.
- [ ] Submit an Assistance request and verify the same request advances through Submitted, Acknowledged, In progress and Completed on both clients.
- [ ] Start a cash attempt, confirm actual collection in Manager and verify the authoritative itemized receipt. Reopen it after closure on the original terminal using the exact visit ID. Test Manager-assisted recovery on a distinct receiving terminal and reject reuse and direct access to the old visit.
- [ ] If a second Customer PC is available, run a second provisioned terminal on another table simultaneously. One Customer PC can instead open two named installations with distinct credentials to exercise isolation locally. Confirm that an active or unsettled visit blocks reassignment. After closure, reassign the terminal, reenter Dine-in and verify the new table. Confirm that sharing a former table assignment grants no access to the other terminal's prior visits or receipts.
- [ ] Restart the backend and both apps. Verify migrations, installed credentials and persisted state. Disconnect and reconnect a client and verify synchronization, including safe recovery when a receipt response is interrupted.

The automated packaged walkthrough runs multiple processes on one host, including distinct Customer terminal credentials and simultaneous visits. Record the three-PC acceptance result separately.
