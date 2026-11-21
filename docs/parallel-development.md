# Parallel development and integration

Keep the verified Customer application at the repository root for now. All feature branches start from the same Customer baseline and retain its Java 21 toolchain, Gradle Wrapper and `com.roseroots.customer` package.

| Branch | Local folder | Responsibility |
| --- | --- | --- |
| `main` | Stable integration branch | Reviewed, verified baselines |
| `feature/customer` | `rose-roots-customer` | Customer JavaFX client |
| `feature/kitchen` | `rose-roots-kitchen` | Kitchen workflows for the staff client |
| `feature/admin` | `rose-roots-admin` | Protected Admin functionality in the same staff client |

Agree on API contracts, DTOs, identity and permission boundaries before integration. The proposed Gradle structure has Customer JavaFX, Staff JavaFX, the Spring Boot server, and shared API contracts. Kitchen and protected Admin functionality belong to the single Staff JavaFX application. The Spring Boot server owns the single PostgreSQL database and its migrations. Both JavaFX clients use the backend APIs. Moving the current Customer app into a Gradle subproject is a separate, coordinated migration.

The [two-PC Windows demonstration plan](two-pc-demonstration.md) records the
deployment layout, REST and WebSocket coordination, and physical-network
acceptance checks for all three owners.

Open the working directories in separate VS Code windows from their parent directory.

```powershell
code --new-window .\rose-roots-customer
code --new-window .\rose-roots-kitchen
code --new-window .\rose-roots-admin
```
