# Dish preparation configuration

In Manager **Menu Management**, use **Add Dish** or **Edit Dish** to select a preparation station and enter a measured base duration in seconds. The upper duration is optional and must be at least the base. In each customization group, use **Advanced option workload** to enter additional seconds per item for the relevant choices. An omitted estimate input remains unknown.

Historical and imported dishes without a preparation profile show **Unconfigured**. Select an existing station and enter measured durations to configure them. Legacy catalog labels and times are never used to infer a profile. **Kitchen Configuration** still manages station activity, parallel slots, Chef assignments and availability.

The catalog save commits the dish, profile, option workload and audit together. A stale edit is rejected and must be reloaded. New orders use the latest authoritative preparation profile. Already accepted orders keep their original station, duration, workload, prices and delay threshold.

## API and migration

Manager `POST /api/v1/admin/menu-items` and `PATCH /api/v1/admin/menu-items/{id}` accept `preparation` containing `stationId`, `durationSeconds`, optional `upperDurationSeconds`, and `options`. Each option contains `groupId`, `optionId`, `lowerSeconds` and `upperSeconds`. IDs must belong to the submitted customization groups. A profile save on an existing dish requires `expectedVersion` from the Admin catalog's item `version`. The old standalone `/admin/prep-times` endpoints are retired.

Admin catalog reads include `preparation`, or null when unconfigured. Legacy response fields `prepMinutes` and `kitchenStation` are derived from the profile and return null without one. New dishes require a profile. Catalog-only edits that omit preparation and the legacy input fields retain the existing profile.

Flyway V22 adds the dish edit version and allows a new accepted order with unknown preparation to have no automatic delay threshold. No accepted-order rows or existing profiles are rewritten.
