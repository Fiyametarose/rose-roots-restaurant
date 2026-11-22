# Staff duty and Waiter Assistance

Chefs and Waiters sign in personally from the existing Kitchen workstation. Sign-in and lock affect the 30-minute authentication session. **Start Shift** and **End Shift** separately change the server schedule. The default is 8 hours, with 4 and 12 hours available. The displayed start and end originate on the server. Relogging preserves the duty record, and expiry takes effect from its stored deadline.

Manager **Kitchen Configuration** retains station qualifications and displays Chef duty status. **Waiter service** displays Waiter duty and the Manager-managed table assignments. A Waiter with no tables has restaurant-wide coverage, including pickup. A table-scoped Waiter can work only the listed dine-in tables. Managers can end duty in an emergency. Station qualifications and table assignments remain after a shift.

Kitchen Assistance stays visible on the shared display. Signing in as an on-duty Waiter enables claiming an unassigned Submitted request by acknowledging it. That Waiter can advance it to In Progress and Completed. Ending a shift leaves existing assignments and audit records intact. A Supervisor can select an eligible on-duty Waiter and enter a reason to reassign unfinished work, including a legacy request already advanced by the shared station. The Customer continues to see the existing four states and does not receive staff ownership details.

## API additions and changes

| Endpoint | Authorization and body |
| --- | --- |
| `GET /api/v1/kitchen/staff-duty` | Personal Chef or Waiter. Returns own duty, assignment names, server time and version. |
| `PATCH /api/v1/kitchen/staff-duty` | Personal Chef or Waiter. Send `hours` of 4, 8 or 12 to start or 0 to end, plus `expectedVersion`. |
| `PATCH /api/v1/kitchen/assistance/{id}` | Personal on-duty Waiter with coverage and ownership, or first acknowledgment of an unassigned Submitted request. Existing status and expected-version body. |
| `GET /api/v1/kitchen/assistance/{id}/eligible-waiters` | Personal Supervisor. Returns available candidates for that request. |
| `POST /api/v1/kitchen/assistance/{id}/reassign` | Personal Supervisor. Send `waiterId`, `expectedVersion` and `reason`. Does not advance status. |
| `GET /api/v1/admin/service-duty` | Manager with the existing Admin session. Lists Waiter duty and tables. |
| `PATCH /api/v1/admin/service-duty/{staff}` | Manager with CSRF. Send `tableIds` and `expectedVersion`. |
| `POST /api/v1/admin/staff-duty/{staff}/end` | Manager with CSRF. Send `expectedVersion` for emergency end. Chef availability also retains its existing Manager end endpoint. |

Shared workstation bearer access to Assistance is read-only. Waiter handoff requires current duty and coverage. Reassignment increments the request version even though the status is unchanged. Clients must refresh on a version conflict and never derive the version from the status.

## Persistent restaurant upgrade plan

The observed persistent deployment uses PostgreSQL 17 at `127.0.0.1:5434` and the live backend at `https://localhost:8090` with Flyway V15. Obtain explicit authorization for the backup, maintenance window and replacement of that backend. Keep any credentials and backups in a restricted local directory. Preserve the existing launcher, TLS material, catalog image paths and bootstrap Manager recovery definition. Record the existing database connection, service ownership, build revision and recovery access without putting secrets in the run log.

1. Take a PostgreSQL 17 custom-format dump with `pg_dump -Fc` using a credential supplied through the operator's protected configuration. Check `pg_restore --list`, then restore to a separately named disposable database or isolated instance and verify it before scheduling the live change. Keep the old server artifact and configuration available for rollback.
2. Rehearse startup of the release artifact against the restored copy. Flyway applies V16 table/terminal metadata, V17 translations, V18 shared workstations, V19 personal Kitchen sessions and item history, V20 Waiter handoff, V21 lifecycle, V22 preparation configuration, V23 receipt recovery and V24 duty and Assistance ownership. Verify records and attachment paths against the baseline. Include Table 07, its two terminals, the six known orders and any data added since that baseline.
3. In the authorized window stop writes and take the final backup. Stop the V15 backend before pointing the new backend at the database. Start the verified artifact with the preserved TLS, binding and Manager recovery configuration. Confirm all migrations, then compare historical entities and check protected Manager access before reopening clients. Rollback requires restoring the final V15 backup into an isolated or approved replacement instance and using the V15 server. Starting the old binary against the upgraded schema is unsafe.
4. Provision through Manager only after the upgrade is accepted. Verify existing `chef1` and `chef2` role/state and reuse them. Create missing `manager`, `supervisor` and `waiter1` after checking conflicting names. If the recovery bootstrap already uses `manager`, retain it while establishing a separately authorized recovery Manager before creating the database-backed `manager`. Check recovery access before retiring any conflicting bootstrap configuration. Set personal passwords through Staff & Security.
5. Add missing Tables 01–05 and three distinct terminals for 01–03. Preserve Table 07, the existing terminals, accepted orders and their history. Issue a shared workstation only if needed, and install terminal/workstation secrets through the Windows installer. Configure the actual active Hot Kitchen and Cold Preparation station qualifications, then have each employee sign in and explicitly start their shift.
