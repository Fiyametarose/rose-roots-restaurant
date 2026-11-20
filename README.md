# Rose & Roots Restaurant Customer Self-Ordering System

This JavaFX desktop application is the customer client for the university group project. Choose a language and either Dine In or Takeaway to browse the demo catalog. Open a card or its photograph to see dish details.

## Versions

| Tool | Version |
| --- | --- |
| Java JDK and Gradle toolchain | 21 |
| JavaFX base, graphics and controls | 21.0.8 |
| Gradle Wrapper | 9.8.1 |
| JUnit Jupiter | 5.13.4 |

Gradle resolves JavaFX from Maven Central and puts the platform-specific modules on the module path. Windows x64 is the development target. The Wrapper pins the Gradle distribution and its SHA-256 checksum.

## Run on Windows

Install an x64 JDK 21 and set `JAVA_HOME` to its installation directory. Run these commands from the workspace root in PowerShell. The first build needs network access to download dependencies.

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat run
.\gradlew.bat run --args="--fullscreen"
.\gradlew.bat runDemo
.\gradlew.bat run --args="--demo-table"
.\gradlew.bat run --args="--demo-takeaway"
.\gradlew.bat run --args="--demo-table --fullscreen"
```

Windowed mode is the default. Press Esc to leave full-screen mode. The scene uses a 1280 × 800 landscape baseline and opens smaller when the display work area requires it. JavaFX layout keeps the content centered as the window resizes.

## Project structure

| Package under `com.roseroots.customer` | Responsibility |
| --- | --- |
| `app` | Entry point, startup wiring and window configuration |
| `navigation` | Route registration and switching within one scene |
| `screens` | Welcome, catalog and dish details |
| `components` | Shared branding, choice bar, catalog shell, food cards, photos and filters |
| `models` | Catalog, translations, food safety, customization and terminal session |
| `state` | Locale, service mode, terminal context and browsing preferences |
| `services` | Settings, asynchronous catalog interface and structured search |
| `services.mock` | Local development catalog |
| `services.api` | Catalog endpoint adapter boundary for Spring Boot |
| `websocket` | Future real-time order subscriptions |
| `utils` | Shared helpers such as message-bundle lookup |

Resources live in `src/main/resources`. The `styles` directory contains the shared forest-green, cream and gold theme. The `i18n` directory contains the default English message bundle. Additional languages can add locale-specific bundles. The `images` and `icons` directories are reserved for design assets.

The bundled `restaurant.properties` controls the name, logo, tagline, provisional languages and currency (KES in the demo). A future adapter can supply the same settings from Spring Boot. Welcome uses isolated illustrations from the supplied reference. English, Amharic and Arabic have Welcome copy and selected catalog labels. Catalog fixture names and descriptions are English. Juba Arabic selection persists with an English fallback. The bundled Noto Sans Ethiopic font renders Amharic and includes its license under `src/main/resources/fonts`. See [Customer interface inventory and plan](docs/customer-interface-plan.md).

## Inspect the demo catalog

1. Launch with `--demo-table`, then choose **Dine In** for **Table 07 · Demo**, or **Takeaway** for a demo pickup reference. A regular launch supports both service modes as local drafts, with optional installation table configuration described below.
2. Browse **Main Courses**, or switch to **Drinks** and pick **Hot Drinks**. Type `cardamom` to find Spiced Chai by ingredient.
3. Open **Filters** and exclude **Milk** to see the empty state for the Spiced Chai search. **Reset filters** clears search, drink subtype, facets and sort while keeping the current top-level category.
4. Select a food photo or **View details**. **Back to menu** restores the query, selected filters, subcategory and sort.
5. **Refresh menu** requests a new catalog snapshot. The current provider supplies 19 fixtures. Sample prices, dietary labels, allergens and preparation times are for development only.

## Terminal sessions and draft cart

- `--demo-table` reads the separate `demo-terminal.properties` fixture. The bundled device ID, table ID and display number are demo values. Production will provision and authenticate each installation through Spring Boot. The client passes service mode to the session adapter and receives the server-resolved assignment and visit.
- `--demo-takeaway` enables a labeled local pickup reference without assigning a demo dine-in table. `--demo-table` enables both scenarios. Demo pickup references last for the current visit and cannot authorize real orders. Normal Takeaway creates a local draft with no pickup reference until a server can issue one.
- Full fixture food-safety metadata appears only when an explicit demo option is enabled. Normal mode withholds unverified fixture dietary and allergen claims, retains the incomplete-information warning, and allows allergy declarations on a local draft.
- Returning to Welcome keeps the selected language, active visit and draft. Reopening the same visit restores its context. Switching modes with items in the draft requires confirmation, and accepted orders belong to their original session ID.
- The demo provider keeps session IDs in process memory. A new visit follows a staff-authorized close. Staff provisioning, persistent order history and payment settlement require the backend.
- The footer shows draft count and total. The demo can add available dishes and review the draft. Checkout, customization and order submission are pending.

## Integration boundaries

See [Parallel development and integration plan](docs/parallel-development.md) for branch ownership and the proposed shared Gradle structure.

- Catalog services can supply dynamic categories, cuisine and dietary filters, allergen details, search and customization options.
- The dining session can retain multiple submitted order IDs while the cart represents the next order in progress. REST and WebSocket integrations can retrieve order status and backend estimates that account for workload, available chefs and preparation times.
- Future service interfaces can cover assistance, payments, receipts and reviews. Screens render data supplied by state and services.
- The Spring Boot backend owns PostgreSQL access. The JavaFX client integrates through REST APIs and WebSockets.

### Normal startup and local table configuration

Run `.\gradlew.bat run` and choose Dine In or Takeaway. Both modes can build a local cart without Spring Boot. The header shows Local draft. Place Order remains unavailable until there is a backend connection and a confirmed session.

The app reads `%USERPROFILE%\.rose-roots\terminal.properties` at startup. It generates a unique installation ID in a new file when that file is absent. To use an admin-provisioned file, set `ROSE_ROOTS_TERMINAL_CONFIG` to its path before launching. For local development, keep the generated ID and assign Table 07 by adding:

```properties
table.id=table-07
table.displayNumber=07
```

Restart after editing. Preserve a different `device.id` on each tablet. In production the operator should provision the ID and protect the file with OS permissions. The future authenticated backend must validate the device and table mapping and issue its own session ID and pickup reference. Local drafts stay in memory. Returning to Welcome and selecting the same mode restores the draft. Changing service modes with items prompts before discarding the previous draft.
