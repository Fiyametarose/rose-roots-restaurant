# Windows showcase setup and startup

The showcase uses the packaged Windows JavaFX Customer and Kitchen apps, the existing Kitchen Manager workspace, one Spring Boot process and one dedicated PostgreSQL 17 database. Customer and Kitchen use HTTPS polling. This guide supersedes the older two-PC launch and legacy staff-token instructions for the showcase.

## First-time setup

1. Build with Java 21 and run `gradlew.bat :installDist :kitchen-app:installDist :server:bootJar`. Keep the packaged client directories together, including their `lib` directories. Each Windows PC needs Java 21. Choose the backend's stable LAN hostname or address, and install a PKCS12 server key store with a matching certificate SAN. Keep the private key and key store on the server PC outside the repository, distributions and synced folders. If the clients need a lab trust store, verify the server certificate fingerprint and use a **public-certificate-only** PKCS12 trust store with its own nonsecret password.
2. On the backend PC, run the installer from Windows PowerShell 5.1. For a separate Docker-managed database, point it at the existing `compose.yaml`. It uses the `rose-roots-showcase` project, a separate volume and loopback port 55434. If using an existing PostgreSQL instance, supply a dedicated showcase database and role. Read the target details before typing **SHOWCASE**. The installer never inserts accounts, tables or orders.

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
7. In **Kitchen Configuration**, assign the two Chefs to active stations and set availability through the end of the showcase. In **Edit Dish**, check each selected dish's station, lower and upper preparation durations, and any option workload. Configure only the dedicated showcase database. Use real orders and confirm cash only after genuine collection in the demo.

### Initial Admin HTTPS launch

In a one-time PowerShell session, open the packaged Kitchen launcher `kitchen-app.bat` in local display mode. Set `ROSE_BACKEND_URL` to the trusted HTTPS origin so Manager sign-in uses it. If Java needs the public-certificate-only trust store, pass its path and independent nonsecret password through the packaged launcher's `JAVA_OPTS`. The installed Kitchen shortcut handles these settings thereafter.

## Daily startup

1. Start Docker Desktop if the backend installation uses Compose. Open **Rose Roots Showcase - Backend - Backend** from Start and leave its console open.
2. Open the **Rose Roots Showcase** Kitchen and appropriate Customer shortcuts on their PCs. Each client uses its installed credential. The shared board and Assistance open without staff sign-in. Chefs, the Supervisor and Waiter sign in individually for their actions, and Manager signs in through the Manager workspace.
3. To move a terminal, finish handoff and settlement and close its visit in **Tables & Dining Sessions**, then save its new assignment. On Customer, return to Welcome and select Dine-in for the next visit. The app fetches the server assignment again using the same installed device credential. Table numbers do not grant access to another terminal's visits or receipts.
4. For an expired or revoked credential, follow the existing Manager provisioning process for a replacement and run `showcase.ps1 -Action ReplaceDeviceCredential -Role Customer -Name Terminal1` with its actual role and name. Use a different label for a replacement terminal. For a workstation rotation while the old credential is active, issue a distinct label and revoke the old one after validating the replacement.

Bring the PCs onto the same trusted LAN, allow the backend's HTTPS port through the server firewall for those clients, and keep PostgreSQL on loopback. Verify the certificate hostname and trust on each PC. This guide covers Windows JavaFX clients.
