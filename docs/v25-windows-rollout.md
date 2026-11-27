# V25 Windows rollout

This checklist is for an approved maintenance window. The running V24 installation continues to use HTTPS on port 8090 and PostgreSQL 17 on loopback port 5434 until the cutover.

## Verify before the window

1. Match the candidate server JAR and both complete Windows distributions to the tested build. Keep their paths stable and retain the V24 JAR, distributions and protected launcher configuration for rollback.
2. Confirm that a custom-format V24 backup restores successfully on isolated PostgreSQL 17. Verify the V25 migration and compare historical rows, including staff, device credentials, stations, visits, orders and any payment data. Confirm Manager recovery against the restored copy using the existing certificate.
3. Identify the three Customer profiles and the shared Kitchen profile under `%LOCALAPPDATA%\RoseRootsShowcase`. Save restricted copies of their existing installation settings. V25 needs a matching Customer distribution and backend. Keep the Kitchen distribution aligned with the tested release.

## Approved cutover

1. Close the desktop clients. Identify the Java process serving the existing backend from its listener and executable, then stop that process and confirm port 8090 is closed. Check that application database connections have ended. Take a fresh custom-format V24 backup and verify its archive listing and checksum.
2. Prepare a separate V25 launcher with the staged `server.jar` and the existing protected database, TLS, Manager recovery and branding/image settings. Keep the V24 launcher for rollback. Launch the candidate against the persistent database on a temporary loopback HTTPS port. Confirm Flyway V25, protected Manager sign-in, and historical counts and relationships before reopening the usual port. Stop the maintenance-port instance and use the V25 launcher to start the candidate on the original HTTPS origin.
3. With all desktop clients closed, update only the `modules` path in each profile's protected `installation.json`. For the three Customer profiles, use the candidate Customer distribution's `lib` directory. For the Kitchen profile, use the candidate Kitchen distribution's `lib` directory. Atomically replace each file inside its existing restricted directory. Keep the profile name, origin, DPAPI credentials, trust store and shortcut target. Reopen the same shortcuts under the same Windows account.
4. On an active authorized visit, place a disposable acceptance order. Confirm that My Orders displays Edit Item and Cancel Item, the price preview matches the bill and Kitchen sees the change through polling. Check the reason displayed when preparation starts. An expired historical RECEIVED item remains ineligible. Keep any real outstanding payments or receipts on their existing visits.

## Rollback

Stop V25 before restoring. Restore and verify the final V24 backup in an approved target before running the V24 executable against it. Restore the original profile module paths and reopen the V24 shortcuts. Reconcile any transactions accepted after the pre-cutover backup before reopening normal traffic.
