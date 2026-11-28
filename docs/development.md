# Development and verification

Use Java 21 and the checked-in Gradle wrapper from the repository root. On Windows use `gradlew.bat`. On macOS or Linux use `./gradlew`. The showcase packages are built and verified on Windows.

## Module commands

```powershell
.\gradlew.bat :customer:build
.\gradlew.bat :customer:test
.\gradlew.bat :customer:run
.\gradlew.bat :customer:runDemo
.\gradlew.bat :customer:installDist
.\gradlew.bat :kitchen-app:build :kitchen-app:installDist
.\gradlew.bat :server:build
.\gradlew.bat build
.\gradlew.bat test
```

The root `build` and `test` commands select those tasks across subprojects. Server PostgreSQL tests require the test datasource variables. Gradle reports them as skipped when the datasource is absent. Use this example in a fresh PowerShell session with Docker Desktop running. It supplies random credentials to an automatically removed PostgreSQL 17 test container.

```powershell
$name = 'rose-review-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
try {
    docker --context desktop-linux run --rm -d --name $name -e POSTGRES_USER=rose_test -e POSTGRES_PASSWORD -e POSTGRES_DB=rose_test -p 127.0.0.1::5432 postgres:17
    if ($LASTEXITCODE) { throw 'Disposable PostgreSQL failed to start.' }
    $deadline = (Get-Date).AddSeconds(30)
    do {
        docker --context desktop-linux exec $name pg_isready -h 127.0.0.1 -U rose_test -d rose_test | Out-Null
        if (!$LASTEXITCODE) { break }
        if ((Get-Date) -gt $deadline) { throw 'Disposable PostgreSQL did not become ready.' }
        Start-Sleep -Seconds 1
    } while ($true)
    $port = (docker --context desktop-linux port $name 5432/tcp).Split(':')[-1]
    $env:ROSE_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:${port}/rose_test"
    $env:ROSE_TEST_DB_USERNAME = 'rose_test'
    $env:ROSE_TEST_DB_PASSWORD = $env:POSTGRES_PASSWORD
    .\gradlew.bat build
    if ($LASTEXITCODE) { throw 'Build failed.' }
} finally {
    docker --context desktop-linux stop $name
    Remove-Item Env:POSTGRES_PASSWORD,Env:ROSE_TEST_DB_URL,Env:ROSE_TEST_DB_USERNAME,Env:ROSE_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
}
```

The server tests own their test schemas. Always point the test variables at a disposable database.

## Connected development

Use a separate disposable PostgreSQL database and a dedicated loopback port for Spring Boot. Set `SPRING_PROFILES_ACTIVE=catalog,lan`, `ROSE_DB_URL`, `ROSE_DB_USERNAME`, `ROSE_DB_PASSWORD`, `ROSE_SERVER_ADDRESS`, `ROSE_SERVER_PORT`, `ROSE_SERVER_KEY_STORE` and `ROSE_SERVER_KEY_STORE_PASSWORD` in the backend process environment. Point `ROSE_IMAGE_DIR` and `ROSE_LOGO_DIR` at disposable locations. Launch with `./gradlew :server:bootRun` or its Windows equivalent.

Use the [HTTPS instructions](showcase-windows.md#lan-and-https-preflight) for a certificate whose SAN matches the test origin and a Java trust store containing its public certificate. Bootstrap a Manager on the disposable database, then provision a test Customer terminal and Kitchen workstation through Manager. The [Windows installer](showcase-windows.md#first-time-setup) accepts separate named profiles. Use test-only names and paths.

For a launch from a development shell, Customer uses `ROSE_ROOTS_BACKEND_URL` and `ROSE_ROOTS_TERMINAL_TOKEN_FILE`. Kitchen uses `ROSE_BACKEND_URL`, `ROSE_KITCHEN_MODE=connected` and `ROSE_KITCHEN_WORKSTATION_TOKEN_FILE`. Pass any required public trust-store settings to the launched JVM. Keep credential files, private stores and Manager bootstrap settings outside the repository.

## Packaging and review checks

| Output | Path |
| --- | --- |
| Customer install distribution | `customer/build/install/rose-roots-customer/` with `bin/rose-roots-customer.bat` |
| Kitchen and Manager install distribution | `kitchen-app/build/install/kitchen-app/` with `bin/kitchen-app.bat` |
| Spring Boot package | `server/build/libs/server.jar` from `:server:bootJar` |

Launch the complete distributions so the launchers can resolve their module paths. For connected desktop review, use a fresh fixture visit. Verify menu and quote, order submission, Kitchen synchronization, Chef duty and preparation, Waiter handoff, accepted-item edits, reviews, cash and receipt readback as applicable. Report which paths ran. The [desktop verification record](connected-desktop-verification.md) and [UI audit](javafx-ui-audit.md) describe earlier evidence and open acceptance checks.

To inspect the bundled Customer catalog fixtures without connecting to a database, run `gradlew.bat :server:importCustomerCatalog -PcatalogMode=inventory`. It uses `customer/` by default. An explicit `-PcustomerCheckout=<project-directory>` selects another Customer source tree with its own `src/` directory.
