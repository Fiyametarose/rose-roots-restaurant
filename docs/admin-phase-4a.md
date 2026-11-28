# Admin Phase 4A · Restaurant Branding & Settings

> Historical phase record. See the [documentation index](README.md) for the current architecture, development and deployment guides.

The Kitchen application's Manager area now includes **Restaurant Branding**. The form edits the name, tagline, description, phone, email, address, logo and a validated theme preset. The public preview updates as the Manager edits. **Reset Unsaved Changes** restores the last loaded or saved values. **Reload saved settings** retrieves the most recent version from the server. Removing the logo and restoring the bundled logo are separate choices. Both take effect on save.

## Existing contracts and ownership

The Customer checkout already defines `RestaurantSettings` and `RestaurantBrand`. Its current adapter reads bundled `restaurant.properties`, a packaged rose mark, and localized copy. It exposes KES, the languages `en`, `am`, `ar`, and `pga`, plus optional local cart charge properties. The existing Kitchen shell uses static branding. The Spring Boot server had no public or Manager branding endpoint. Phase 4A uses the shared server and security chain and keeps the Customer and Kitchen-owned screens as they were.

The current catalog currency is KES. The response reports the supported service modes `DINE_IN` and `TAKEAWAY` and the existing Customer language list. Billing calculations and tax, service charge and tipping policy retain their existing behavior. The form shows operational information read-only.

## REST contract

All routes use the configured backend origin. The public settings response is available without a session and can be revalidated on each refresh.

```http
GET /api/v1/settings/public
GET /api/v1/settings/logo/default
GET /api/v1/settings/logo/{uuid}
```

Example public response for a fresh database:

```json
{
  "name": "Rose & Roots",
  "descriptor": "Restaurant",
  "tagline": "Experience the rich flavors of Ethiopia, South Sudan and the world, at your table.",
  "description": null,
  "telephone": null,
  "email": null,
  "address": null,
  "logoReference": "/api/v1/settings/logo/default",
  "theme": {"preset": "FOREST_GOLD", "primary": "#0F3D29", "background": "#F7F2E8", "accent": "#A77936"},
  "currency": "KES",
  "languages": ["en", "am", "ar", "pga"],
  "serviceModes": ["DINE_IN", "TAKEAWAY"]
}
```

`GET /api/v1/admin/settings` returns a version, the same public payload inside `brand`, and the available palettes inside `themes`. The Manager shell uses its existing session cookie and obtains CSRF tokens from `GET /api/v1/admin/csrf`. To replace a logo, send the JPEG or PNG bytes to `POST /api/v1/admin/settings/logo` with the CSRF header. The response is HTTP 201 with a root-relative `logoReference`. Uploading does not change the active logo.

Send the complete branding form and last retrieved version in a Manager `PATCH /api/v1/admin/settings` request. Optional values may be null. Set `logoReference` to null to remove a logo, the default path to restore the bundled logo, or the generated logo reference to activate a replacement.

```http
PATCH /api/v1/admin/settings
Content-Type: application/json
X-CSRF-TOKEN: <token from the current Manager session>
```

```json
{
  "version": 0,
  "name": "Rose & Roots",
  "tagline": "Freshly prepared, warmly served.",
  "description": null,
  "telephone": null,
  "email": null,
  "address": null,
  "theme": "FOREST_GOLD",
  "logoReference": "/api/v1/settings/logo/default"
}
```

The successful response has the same schema as the Manager GET. Validation errors return HTTP 400. A concurrent update returns HTTP 409 and requires a fresh version. Unauthenticated admin requests are rejected, Kitchen Staff cannot establish a Manager session, and authenticated writes require CSRF.

## Persistence and security

Flyway `V5__restaurant_branding.sql` adds `restaurant_settings`, `restaurant_logo`, and `restaurant_settings_audit` to the existing catalog database. The JPA entities are `RestaurantSettings`, `RestaurantLogo`, and `RestaurantSettingsAudit`. Settings changes serialize on the database row, use a JPA version and commit the change and its audit record in the same transaction. The audit stores the authenticated Manager, timestamp, and before/after values. Failed updates and unchanged saves create no settings audit record.

The server reuses the catalog's bounded JPEG/PNG decoder. It re-encodes logos as PNG, preserves transparency and proportions, and scales images down only when an edge exceeds 1,024 pixels. The default file directory is the `branding` subdirectory of the catalog image directory. `ROSE_LOGO_DIR` can override it. Back up this directory together with PostgreSQL. Previously uploaded files are retained for stable references and audit history. There is no automated cleanup of superseded or unreferenced uploads in this phase.

## Customer and Kitchen integration

Members 1 and 2 can fetch `GET /api/v1/settings/public` at startup and again on reconnect or an agreed refresh action, using the existing configured HTTPS origin and trust store. Resolve `logoReference` against that origin, treat null as no logo, and keep the last successful settings for temporary outages. The endpoint returns saved changes without a client rebuild. The clients' shared runtime refresh behavior remains an integration dependency. Any future notification should use the agreed shared WebSocket contract. This phase does not require another server or database.

## Verification

The initial 60-test baseline passed. The complete build and Customer and Kitchen/Admin Windows launcher distributions passed using:

```powershell
.\gradlew.bat build :customer:installDist :kitchen-app:installDist
```

The final Gradle test results show 62 passed, 0 failed, and 0 skipped. PostgreSQL tests used disposable schemas. They cover anonymous and Kitchen access rejection, CSRF, public settings and logo retrieval, PNG alpha, JPEG replacement, missing and invalid images, failed updates, stale versions, removal, audit recording and restart persistence. A read-only catalog check on the Rose & Roots container found 19 dishes and beverages, 19 photograph records and seven categories in the persistent schema. FarmSra AI was outside the verification environment.

The generated `rose-roots-customer.bat` and `kitchen-app.bat` each opened their Windows desktop window. The Admin JavaFX run exercised Manager sign-in, form validation, reset, the native file chooser, preview, save, and the narrow layout. The packaged Spring Boot server applied V5 to a disposable schema with the `catalog,lan` profiles. An independent HTTPS client fetched the saved public JSON and PNG, then confirmed both were byte-for-byte identical after the server restarted. This used the LAN HTTPS profile with a trusted test certificate on one Windows PC. The two-physical-PC demonstration remains separate.

- [Populated branding screen](phase4a-screenshots/branding-populated.png)
- [Logo replacement preview](phase4a-screenshots/branding-logo-replacement.png)
- [Required-name validation](phase4a-screenshots/branding-validation.png)
- [Saved settings](phase4a-screenshots/branding-saved.png)
- [Narrow window](phase4a-screenshots/branding-narrow.png)

## Source changes

- Kitchen/Admin JavaFX workspace, navigation, HTTP client and stylesheet in `kitchen-app/src/main`.
- Spring controller, JPA entities and repositories, settings service and logo storage in `server/src/main/java/com/roseroots/server/settings`.
- Shared decoder in `server/src/main/java/com/roseroots/server/media`, used by settings and the existing menu image service.
- Flyway V5, catalog-profile configuration, packaged default logo wiring in `server/build.gradle`, and isolated database coverage in `CatalogPostgresTest`.
- This guide, Windows screenshots, and the branding check in the two-PC demonstration guide.
