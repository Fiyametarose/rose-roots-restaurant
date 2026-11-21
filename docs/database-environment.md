# Rose & Roots database operations

The [Compose file](../compose.yaml) runs PostgreSQL 17 in project
`rose-roots`, service `db`, container `rose-roots-db`. The database
and its dedicated role are named `rose_roots`. The named volume
`rose-roots_postgres_data` stores the PostgreSQL data directory.
The container is the sole member of `rose-roots_catalog`.

Spring Boot runs on the Windows host and connects to
`jdbc:postgresql://127.0.0.1:5434/rose_roots`. Docker publishes container
port 5432 to Windows loopback only. Port 5434 was selected because
FarmSra AI already uses port 5433 on this host. If another PC needs
a different available host port, set `ROSE_DB_PORT` and update the
JDBC URL to match. The desktop applications talk to Spring Boot.

## Start on Windows

Run from the repository root with Docker Desktop's Linux engine running.
Keep the existing password file if the volume has previously been initialized.
The image uses the file to initialize a new database role. Changing the
file alone does not rotate credentials on an initialized volume.

```powershell
if (!(Test-Path -LiteralPath '.env.rose-roots-db-password')) {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    [IO.File]::WriteAllText(
        (Join-Path $PWD '.env.rose-roots-db-password'),
        [Convert]::ToBase64String($bytes))
}
docker --context desktop-linux compose -f compose.yaml -p rose-roots up -d --wait
```

The Git-ignored file is mounted as a Compose secret and read by the
image through `POSTGRES_PASSWORD_FILE`. Keep it accessible to the
Windows user running the backend. The dedicated database role is the
image's bootstrap role, with administrative privileges inside this
isolated PostgreSQL instance. [Docker service configuration](https://docs.docker.com/reference/compose-file/services/) · [Official PostgreSQL image](https://hub.docker.com/_/postgres)

In the backend PowerShell window, configure the existing Manager
account as described in the [Phase 1 guide](admin-phase-1.md) and start
Spring Boot.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'catalog'
$env:ROSE_DB_URL = 'jdbc:postgresql://127.0.0.1:5434/rose_roots'
$env:ROSE_DB_USERNAME = 'rose_roots'
$env:ROSE_DB_PASSWORD = (Get-Content -LiteralPath .env.rose-roots-db-password -Raw).Trim()
.\gradlew.bat :server:bootRun
```

For HTTPS use `catalog,lan` and the certificate settings in the
[Windows demonstration guide](two-pc-demonstration.md). To stop only
the database service while retaining its named volume, use
`docker --context desktop-linux compose -f compose.yaml -p rose-roots stop db`.

## Audit findings

- Phase 2A initially attempted to start Windows PostgreSQL binaries
  provided by an embedded test dependency. The cached executable had no
  Authenticode signature. Windows Code Integrity event 3077 recorded the
  block on November 20, 2026, for `embedded-pg/.../bin/postgres.exe`.
  This attempt failed before integration tests could use the database.
- Successful Phase 2A integration tests used the official Docker
  `postgres:17` image, PostgreSQL 17.12, with an ephemeral loopback
  port and a randomly named disposable schema in the `postgres`
  database. The test report present at the start of this audit recorded host port 49929. The
  independent screenshot run recorded port 57532 in its fixture log.
  The test containers used Docker's default bridge and had no dedicated
  durable volume. Their transient container names were generated per
  run and are absent from current Docker inventory.
- The initial audit found no listening Rose & Roots backend, native
  PostgreSQL service, or existing Rose & Roots Compose resources. The
  recorded Phase 2A fixtures were disposable. No durable Rose & Roots
  source database was identified for migration.
- FarmSra AI's healthy `farmsra-ai-db-1` container uses
  `127.0.0.1:5433`, `farmsra-ai_default`, and
  `farmsra-ai_postgres_data`. This audit inspected Docker metadata
  only. Phase 2A logs show separate test ports and containers. Those
  logs establish which database the recorded runs used.

## JPA and test isolation

[server/build.gradle](../server/build.gradle) declares Spring Data JPA,
Flyway, Flyway's PostgreSQL module, and the PostgreSQL JDBC driver.
Hibernate is the JPA provider. The [catalog package](../server/src/main/java/com/roseroots/server/catalog)
contains the Jakarta Persistence entities `CategoryEntity`,
`DishEntity`, and `DishAudit`, plus the three `JpaRepository`
interfaces in `CatalogRepositories.java`.

[application-catalog.properties](../server/src/main/resources/application-catalog.properties)
configures the datasource and instructs Hibernate to validate the schema
managed by [Flyway migrations](../server/src/main/resources/db/migration).
`CatalogService` uses repeatable-read snapshots, transactional writes
and audit rows, and a mandatory caller transaction for the orderability
check. Catalog writes serialize through the database lock row.

`CatalogPostgresTest` uses the configured `ROSE_TEST_DB_*` datasource
and creates its own `rr_test_…` schema. It restarts Spring Boot against
that schema and removes only that generated schema at teardown. To run
against this Rose & Roots instance after configuring the datasource above:

```powershell
$env:ROSE_TEST_DB_URL = $env:ROSE_DB_URL
$env:ROSE_TEST_DB_USERNAME = $env:ROSE_DB_USERNAME
$env:ROSE_TEST_DB_PASSWORD = $env:ROSE_DB_PASSWORD
.\gradlew.bat build :installDist :kitchen-app:installDist
```

During the audit, all 40 tests passed, including all three PostgreSQL
integration tests at port 5434. Hibernate reported version 7.2.25.Final.
The test suite created and edited a dish through the Manager REST API,
verified its data and audit history after restart, and removed its schema.

The packaged backend started twice with the combined `catalog,lan` profiles.
Trusted HTTPS returned the same public catalog both times. Flyway applied V1
and V2 on the first start and reported the schema current on the second.
The persistent schema held seven seeded categories, zero dishes, and zero
audit rows. No disposable test schemas remained. The FarmSra container ID,
start time, and running status were unchanged during setup and verification.
