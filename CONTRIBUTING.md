# Contributing to Rose & Roots

Install Git and a Java 21 JDK. Use the repository's Gradle wrapper. Docker with PostgreSQL 17 and a Windows desktop are needed for the connected Windows verification path.

```powershell
git clone https://github.com/Fiyametarose/rose-roots-restaurant.git
cd rose-roots-restaurant
git switch -c feature/integration --track origin/feature/integration
git switch -c <topic-branch>
.\gradlew.bat build
```

If the integration branch already exists locally, switch to it and use `git pull --ff-only` before creating the topic branch. Use the [development guide](docs/development.md) to test one module or to add disposable PostgreSQL coverage.

## Coordinate before changing shared behavior

| Area | Review contact |
| --- | --- |
| Customer JavaFX and client state | Customer module reviewer, to be confirmed |
| Kitchen and Manager JavaFX | Staff application reviewer, to be confirmed |
| Spring Boot, database and contracts | Backend reviewer, to be confirmed |

Coordinate REST DTOs, API changes, migrations and shared resources with all affected reviewers. Keep accepted-order snapshots and existing migrations compatible. Add tests for changed behavior in the owning module. Verify connected behavior with disposable records and report skipped integration or packaged desktop checks explicitly.

## Submit a reviewable change

1. Keep the branch focused on one engineering outcome. Use concise action-oriented commit subjects such as “Preserve Customer launcher name after module move.”
2. Bring updated integration changes into your topic branch before review. Resolve conflicts by preserving both branches' required behavior and rerun the affected tests. When a file was moved, carry edits into the new path and check that the old path does not reappear.
3. Open the pull request against `feature/integration`. Describe the reason, changed modules, contract or migration impact, commands and test results, and remaining limitations.
4. Reviewers should check behavior, security boundaries, migrations, launcher and resource paths, and whether the evidence supports the claimed outcome.

Keep passwords, token files, recovery codes, database dumps, private keys and workstation or IDE settings out of commits. Keep build outputs untracked. Use a disposable database and separate test profiles throughout development. The persistent restaurant installation requires an authorized maintenance procedure.
