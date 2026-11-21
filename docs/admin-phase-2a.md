# Admin Phase 2A

For the later photograph workflow and its storage settings, see
[Admin Phase 2B](admin-phase-2b.md).

The catalog uses Spring Data JPA and Flyway in the existing Spring Boot server.
On PC 2, point it at the shared PostgreSQL database. Both JavaFX applications
reach the server over HTTP in local development or HTTPS for the [two-PC demonstration](two-pc-demonstration.md).
The server's `catalog` profile enables the datasource and catalog routes. The
Phase 1 local and LAN profiles can still start without a datasource.

## Run with PostgreSQL

Start the dedicated PostgreSQL service on PC 2 using the
[database operations guide](database-environment.md). Set these variables in
the same PowerShell window as the Manager credentials from the
[Phase 1 guide](admin-phase-1.md).

```powershell
$env:SPRING_PROFILES_ACTIVE = 'catalog'
$env:ROSE_DB_URL = 'jdbc:postgresql://127.0.0.1:5434/rose_roots'
$env:ROSE_DB_USERNAME = 'rose_roots'
$env:ROSE_DB_PASSWORD = (Get-Content -LiteralPath .env.rose-roots-db-password -Raw).Trim()
$env:ROSE_CATALOG_CURRENCY = 'KES'
.\gradlew.bat :server:bootRun
```

Match the JDBC port to the local Compose port. Start Kitchen in a second shell with
`.\gradlew.bat :kitchen-app:run` and open **Manager sign-in**.
For the two-PC demo use `SPRING_PROFILES_ACTIVE=catalog,lan` and configure
the existing certificate, LAN bind address, and client origin as documented
in the deployment guide. Keep the database port closed to the LAN.

The configured ISO currency applies to the whole catalog. Prices have two
decimal places. Configure the currency before entering production prices.
Changing the currency setting changes their displayed denomination.
There is no automatic conversion.

## Database

- `V1__catalog.sql` adds `catalog_category`, `catalog_item`,
  `catalog_item_audit`, and a singleton `catalog_write_lock`.
  PostgreSQL enforces foreign keys, unique sibling names, basic field ranges,
  enum checks, and nonnegative numeric prices. Flexible ingredient, dietary,
  and customization lists use PostgreSQL JSONB.
- `V2__seed_catalog_categories.sql` seeds Starters, Main Courses, Desserts and
  Drinks, with Hot Drinks, Cold Drinks and Shakes under Drinks.
- Category changes, including parent changes, serialize through the lock
  row before checking for cycles. A hidden or archived ancestor suppresses
  its category subtree and its dishes from the published catalog. Restoring
  the ancestor preserves earlier child visibility choices. There is no
  hard-delete route. Counts reflect the number of dishes directly assigned
  to each category, with management counts including archived dishes.
- Price, availability, and archive changes write an audit row in the same
  transaction with the manager's identity, time, prior and new values, and
  a reason if supplied. Hibernate validates the Flyway-managed schema at
  startup.

Coordinate migration numbering before merging with migrations from other
feature branches.

## REST contract

All routes start with `/api/v1`.

| Method and route | Access and response |
| --- | --- |
| `GET /categories` | Public list of visible categories and counts |
| `GET /menu-items` | Public list of published dishes, including items marked `SOLD_OUT` |
| `GET /menu` | Public consistent snapshot containing `currency`, `categories`, and `items` |
| `GET /admin/catalog` | Manager snapshot including hidden and archived records |
| `POST /admin/categories` | Create a category. Returns HTTP 201 and the saved category |
| `PATCH /admin/categories/{id}` | Save the category form. Requires all category form fields and returns the saved category |
| `POST /admin/menu-items` | Create a dish. Returns HTTP 201 and the saved dish |
| `PATCH /admin/menu-items/{id}` | Save the complete dish form. The record's archive status is preserved |
| `PATCH /admin/menu-items/{id}/trading` | Change any combination of price, availability, or archive status. Omitted fields retain their values |

Protected requests use the Manager session cookie. Send a fresh
`X-CSRF-TOKEN` from `GET /admin/csrf` for mutations. Login and logout follow
Phase 1. Validation problems return HTTP 400, unknown records return 404,
and duplicate sibling names return 409. For example,
`{"message":"A sibling section already has that name."}`.

### Category example

Request body for `POST /admin/categories`. Omit or set `parentId` to null
for a root category. Lower display-order numbers sort first among siblings.

```json
{
  "name": "Smoothies",
  "parentId": "00000000-0000-0000-0000-000000000004",
  "displayOrder": 40,
  "status": "VISIBLE"
}
```

Response example.

```json
{
  "id": "6ee35e80-acb0-4a92-87cd-77bfed2846d8",
  "parentId": "00000000-0000-0000-0000-000000000004",
  "name": "Smoothies",
  "displayOrder": 40,
  "status": "VISIBLE",
  "itemCount": 0
}
```

### Dish and trading examples

Request body for `POST /admin/menu-items`. The response adds the generated
`id` and `archived: false`. Existing image references can be returned by
the API. Admin previews only references from the backend's own origin.

```json
{
  "categoryId": "6ee35e80-acb0-4a92-87cd-77bfed2846d8",
  "name": "Mango Smoothie",
  "description": "Mango and banana blended until smooth.",
  "cuisine": "INTERNATIONAL",
  "price": 420.00,
  "servingSize": "300 ml",
  "ingredients": ["Mango", "Banana"],
  "allergens": [],
  "dietaryTags": ["VEGAN"],
  "prepMinutes": 5,
  "kitchenStation": "Beverages",
  "availability": "AVAILABLE",
  "customizationGroups": [
    {
      "id": "extras",
      "name": "Extras",
      "selection": "EXTRA",
      "minimum": 0,
      "maximum": 1,
      "choices": [{"id": "banana", "name": "Extra banana", "priceAdjustment": 40.00}]
    }
  ],
  "imageReference": null,
  "displayOrder": 10
}
```

A trading update can use
`{"price":450.00,"availability":"SOLD_OUT","reason":"Updated ingredient cost"}`.
Use `{"archived":true}` to archive and `{"archived":false}` to restore.
The response is the saved dish.

## Integration and verification

The public format follows the Customer model's category relationships and
enum vocabulary. Member 1 still needs to connect the Customer launcher to
these endpoints and map the current single-language labels into the existing
`LocalizedText` models. Treat the listed allergens as declared ingredients.
Keep cross-contact and allergen completeness unknown where the catalog
supplies no evidence.

The order service should call `CatalogService.requireOrderable` within the
same transaction used to accept the order. Copy the returned dish price and
the confirmed option adjustments into the order's immutable snapshot. The
catalog service rejects sold-out and archived dishes and dishes under hidden
or archived categories. The order service remains responsible for validating
option selections and totals, applying this check before acceptance, and never
recalculating historic orders from mutable catalog prices.

Member 2 should connect catalog change signaling to the shared WebSocket
infrastructure after successfully committed writes. Clients should refetch
the authoritative public snapshot. The current Customer and Kitchen launchers
require their owner-side integrations for the physical two-PC demonstration.

Run `.\gradlew.bat build :installDist :kitchen-app:installDist` on Windows.
To include the PostgreSQL suite, set `ROSE_TEST_DB_URL`,
`ROSE_TEST_DB_USERNAME`, and `ROSE_TEST_DB_PASSWORD` for a disposable PostgreSQL
database with a role that can create schemas. The suite makes and cleans up
its own randomly named schema. Without a test URL, those tests are skipped.
The suite tests the public and manager APIs, migrations, changes and audit rows,
and reads saved records after stopping and restarting Spring Boot against
the same test database. The price-snapshot test protects the captured catalog
value. The final historical order assertion depends on the real order service.

The Phase 2A verification used PostgreSQL 17.12. Flyway applied both catalog
migrations on the first startup. After Spring Boot restarted against that
same schema, the public API returned the same dish ID, edited description,
updated price, stock state, and customization data. A direct PostgreSQL
query verified the three associated trading and archive audit rows.

The following captures show JavaFX running against the `catalog,lan`
profiles over HTTPS with dishes loaded from a disposable PostgreSQL database.

- [Menu Management](phase2a-screenshots/02-menu-management.png)
- [Categories & Drink Sections](phase2a-screenshots/03-categories-drink-sections.png)
- [Add / Edit Dish](phase2a-screenshots/04-add-edit-dish.png)
- [Prices & Availability](phase2a-screenshots/05-prices-availability.png)
- [Narrow Prices & Availability layout](phase2a-screenshots/05-prices-availability-narrow.png)
