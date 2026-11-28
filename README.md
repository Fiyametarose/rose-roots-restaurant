# Rose & Roots — Restaurant Management and Self-Ordering System

Rose & Roots is a university group project for running restaurant visits from menu browsing through preparation, service and settlement. It supports **dine-in and takeaway** through two desktop applications connected to one restaurant backend.

| Gradle project | Responsibility |
| --- | --- |
| `:customer` | JavaFX menu, customization, allergy disclosure, checkout, item editing and cancellation before preparation starts, order tracking, Assistance, cash payment and receipts, and separate dish, order and restaurant reviews |
| `:kitchen-app` | Shared Kitchen board, personal staff sign-in and shifts, station-based preparation, Waiter handoff, and the protected Manager workspace |
| `:server` | Spring Boot REST API, server-side pricing and authorization, transaction processing and Flyway-managed PostgreSQL persistence |

The project uses **Java 21**, **JavaFX 21.0.8**, **Gradle 9.8.1** via the wrapper, **Spring Boot 4.0.10**, **PostgreSQL 17** and **JUnit 5**. Connected clients use authenticated HTTPS REST requests and polling. Windows is the packaged showcase target.

## How a visit works

- **Customer** uses a provisioned terminal, builds a cart and receives a server-priced quote. Accepted items can be edited or cancelled while still RECEIVED and otherwise eligible. The terminal tracks preparation and handoff, can request Assistance, reviews the running bill and can retrieve a verified cash receipt. Review eligibility follows actual handoff.
- **Chef** starts a personal shift, claims an item at an assigned station and records preparation. A pending allergy review blocks preparation until an authorized Chef decides it.
- **Waiter** starts a personal shift, handles assigned Assistance and explicitly confirms dine-in service or takeaway collection after food is ready.
- **Supervisor** oversees workload, records reasons for Rush or Delayed changes and can reassign eligible preparation work and Assistance.
- **Manager** manages catalog, translations, branding, staff, workstations, tables, terminals, stations and visits. The Manager workspace also handles cash confirmation, authorized receipt recovery and review reporting.

The [architecture guide](docs/architecture.md) shows where these responsibilities live and how they share data.

## Repository layout

```text
customer/       Customer app, resources, local providers and tests
kitchen-app/    Kitchen and Manager app, resources and tests
server/         REST backend, migrations and integration tests
gradle/         Gradle wrapper and shared desktop dependency configuration
scripts/        Windows showcase installation and launch
docs/           Current guides, API contracts and historical project records
```

## Build and run on Windows

Install a Java 21 JDK, Git and access to Maven Central for the first build. For connected checks, use a separate PostgreSQL 17 instance and a trusted HTTPS certificate. Docker Desktop with the Linux engine is one way to run the disposable database.

```powershell
java -version
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat :customer:runDemo
.\gradlew.bat :kitchen-app:run
```

Customer demo mode provides a local menu and cart. For a local Kitchen demonstration when connected environment settings are present, launch from a fresh PowerShell session with `ROSE_KITCHEN_MODE=local`. See [development and verification](docs/development.md) for module commands, local settings, tests and Windows package locations.

For connected development, start **disposable PostgreSQL → Spring Boot → desktop clients**. Provision dedicated fixture credentials through Manager, use the matching trusted HTTPS origin and keep secrets in protected local configuration. The live restaurant database, backend and installed profiles are operational assets. The [Windows showcase guide](docs/showcase-windows.md) covers intentional deployment separately.

## Working with the team

Use [CONTRIBUTING.md](CONTRIBUTING.md) for review workflow, module coordination and secrets handling. The [documentation index](docs/README.md) separates current guides, wire contracts, deployment procedures and earlier phase records. Maintainer and module contacts can be added once the team confirms them.
