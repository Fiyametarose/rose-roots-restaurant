# Admin Phase 2C catalog import and handoff

> Historical phase record. See the [documentation index](README.md) for the current architecture, development and deployment guides.

## Source inventory

The import uses the local Customer checkout, branch `feature/customer`,
commit `e5f58fa` at audit time. Its executable source catalog is
`MockMenuService(true)`, and `restaurant.properties` specifies KES.
The import task compiles only the Customer catalog and model classes it needs
into a separate build output. It reads the original bundled photographs
directly. The Customer checkout is read-only during this process.

| Category | Dishes and original prices in KES |
| --- | --- |
| Starters (4) | Lentil Sambusa 490, Black-Eyed Pea Salad 520, Azifa Lentil Salad 480, Tomato Bruschetta 560 |
| Main Courses (6) | Doro Wat 1450, Kisra with Bamia 1250, Grilled Fish 1750, Beef Tibs 1560, Groundnut Stew 1190, Herb Chicken 1390 |
| Desserts (3) | Berry Cheesecake 620, Tropical Fruit Plate 460, Chocolate Mousse 590 |
| Drinks (0 direct, 6 in children) | Hot Drinks, Cold Drinks, Shakes |
| Hot Drinks (2) | Ethiopian Coffee 350, Spiced Chai 300 |
| Cold Drinks (2) | Hibiscus Iced Tea 450, Fresh Mango Juice 550 |
| Shakes (2) | Avocado Shake 750, Chocolate Shake 700 |

There are 19 items and 19 original photographs. The Customer source includes
English names, summaries and descriptions, cuisine, ingredients, and original
display order. Berry Cheesecake is sold out and the other 18 are available.
Doro Wat has spice, portion, side, and extra groups. Grilled Fish has a side
group. Doro Wat's original positive adjustments are KES 300 for Large, 110
for Roasted Potatoes, 90 for Extra Egg and 120 for Yogurt.

The Customer source contains no dedicated serving-size or station fields.
Berry Cheesecake and Chocolate Mousse have no preparation duration. Those
unknown operational fields remain null. Descriptions can contain portion
context, but the import does not infer structured serving sizes from prose.
Dish translations were not supplied. The Customer checkout contains Arabic
and Amharic category labels, and the existing backend category contract
continues to publish English category names.

The checkout labels its safety fields as development fixtures. The import
retains supplied ingredients and preserves sample allergen, cross-contact,
dietary and option-allergen claims under the explicitly unverified
`sourceDetails` field. Manager-verified `allergens` and `dietaryTags`
are empty on these imports. Treat them as unreviewed, including where the
source's completeness flag was true. Managers must verify ingredients,
declared allergens, facility cross-contact, and option allergens before
relying on them for service. Source spice levels, highlights and preparation
estimates should also be reviewed operationally.

## Import operations

1. Start the existing Rose & Roots backend once with its `catalog` profile
   to apply Flyway migrations. See the [database guide](database-environment.md).
2. Use the same datasource and image-directory settings for import and backend.
   On this host the datasource is `jdbc:postgresql://127.0.0.1:5434/rose_roots`.
   Set `ROSE_IMAGE_DIR` to the dedicated persistent directory or use the same
   default as the backend.
3. From the integrated repository root, use these PowerShell commands.

```powershell
.\gradlew.bat :server:importCustomerCatalog -PcatalogMode=inventory
.\gradlew.bat :server:importCustomerCatalog -PcatalogMode=dry-run
.\gradlew.bat :server:importCustomerCatalog -PcatalogMode=apply
```

Pass `-PcustomerCheckout=<path>` if the checkout is not at the default
`customer/`. Inventory lists source inputs without connecting
to PostgreSQL. Dry-run decodes the photos and validates rows, hierarchy,
prices, options and currency, then reports prospective work. Neither
inventory nor dry-run applies migrations or writes catalog and image data.
Apply repeats preflight before entering the catalog transaction.

Flyway V4 adds unique source identifiers to dishes, preserves source
metadata in JSONB, permits unknown serving size, station and prep duration,
and records SHA-256 digests for deduplicating byte-identical imported images.
The original seven category UUIDs and existing records are preserved.
New dishes use the name-based UUID derived from UTF-8
`rose-roots/customer/dish/<customer-id>`; the source slug is also stored
as `customerSourceId`. Newly imported categories outside the seeded set use
`rose-roots/customer/category/<customer-id>` unless matched to an existing
same-named category under the mapped parent.

Repeats skip known source IDs and leave manager changes alone. Existing dish
name or ID collisions are reported for manual resolution. The importer
does not reassign those records. Image IDs, normalized storage filenames,
and metadata use the Phase 2B image service. On an unsuccessful transaction,
no catalog changes commit and newly moved files are removed on confirmed
database rollback. An interrupted process can leave an unreferenced file.
See the [image operations guide](admin-phase-2b.md) for retention and backup.

## Customer and Kitchen handoff

**Member 1.** Customer should fetch the shared REST catalog and map the
backend's category and dish UUIDs, retaining `customerSourceId` as the
legacy correlation key. Use the backend UUID for new order submissions.
A terminal can fetch a consistent snapshot from `GET /api/v1/menu`, or
fetch `GET /api/v1/categories` and `GET /api/v1/menu-items` separately.
Resolve `imageReference` against the configured `ROSE_BACKEND_URL` origin
before passing it to the existing image component. Managed photographs use
`GET /api/v1/menu-images/{id}`.

A shortened published item looks like this:

```json
{
  "id": "<backend UUID>",
  "customerSourceId": "doro-wat",
  "categoryId": "00000000-0000-0000-0000-000000000002",
  "name": "Doro Wat",
  "price": 1450.00,
  "availability": "AVAILABLE",
  "archived": false,
  "imageReference": "/api/v1/menu-images/<image UUID>",
  "customizationGroups": [
    {
      "id": "extras",
      "name": "Extras",
      "selection": "EXTRA",
      "minimum": 0,
      "maximum": 2,
      "choices": [{"id": "extra-egg", "name": "Extra egg", "priceAdjustment": 90.00}]
    }
  ]
}
```

Use the snapshot currency `KES` and exact decimal arithmetic. Enforce group
selection bounds and calculate option adjustments on the server. Sold-out
dishes remain visible for display, while archived dishes and dishes under
hidden or archived categories are omitted. Use
`CatalogService.requireOrderable` within the order acceptance transaction,
and store immutable item and option prices in the order snapshot.
Use the manager-approved allergen and dietary fields only in conjunction
with the restaurant's safety review. Source claims remain informational and
option cross-contact can still be unknown.

**Member 2.** Kitchen should consume station and preparation data through the
agreed API and retain null for unassigned source values. Coordinate
catalog-changed signals on the shared authenticated WebSocket
infrastructure after successful catalog commits. Clients should refetch
the REST snapshot and resynchronize after reconnect. Catalog import adds
no separate WebSocket infrastructure. HTTPS/WSS and the shared origin on
PC 2 remain as defined in the [two-PC guide](two-pc-demonstration.md).

## Persistent import result

The dry-run planned 19 dishes and skipped seven existing categories.
Apply created 19 dishes and 19 JPEG files, skipped the seven categories,
and reported zero conflicts and zero failures. A second apply skipped all
26 records. Existing category rows matched their pre-import checksum and
the unchanged audit count was zero. The persistent catalog holds 18
available dishes, one sold-out dish, and no archived dishes. The public
menu and all 19 image URLs still worked after Spring Boot restarted.

The build and both Windows launcher distributions passed. All 47 tests
passed using the dedicated PostgreSQL instance and disposable schemas for
integration tests. The persistent catalog remains available at port 5434.
The running JavaFX client displayed thumbnails, search and category/cuisine
filters, the original dish ingredients and options, and catalog-backed
sold-out status. In a disposable copy, a manager edit and replacement
photo survived backend restart. Archiving that imported dish removed it
from the public menu, and restoring it made it public again.

Runtime captures from the persistent catalog:

- [Menu Management](phase2c-screenshots/populated-menu-management.png)
- [Categories & Drink Sections](phase2c-screenshots/populated-categories.png)
- [Add / Edit Dish](phase2c-screenshots/populated-dish-editor.png)
- [Prices & Availability](phase2c-screenshots/populated-prices.png)
- [Dashboard](phase2c-screenshots/populated-dashboard.png)
