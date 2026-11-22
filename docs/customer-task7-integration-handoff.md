# Customer integration handoff

Customer cart estimates use the shared draft wait-estimate endpoint with the same session, lines, customization selections, and visit allergy declaration as checkout. The server calculates the range and blocks estimation while an allergy decision is pending. Connected cart estimates refresh at expiry and retry after 15 seconds when unavailable. Accepted-order estimates are read separately through the published [waiting-time contract](dynamic-wait-estimates.md).

Connected menus refresh when opened and again 15 seconds after each completed read while visible. Local menus keep manual refresh. Catalog refresh preserves the last successful menu on a transient failure, along with the translation and branding behavior in the [catalog publication contract](catalog-publication.md).

The [cash-first payment OpenAPI](payment-settlement-v1.openapi.json) and [settlement guide](payment-settlement-v1.md) are published. Customer payment and receipt UI wiring and physical two-PC acceptance remain open. Locale-specific option names and problem messages are outside this integration.

The previously reported Windows exception was raised by Glass Windows UI Automation during shutdown of the screenshot harness, after screenshots were saved. The recorded stack passes through WinAccessible, UiaRaiseAutomationEvent and Scene.setWindowFocused after the native view closed. Closing the modal first and then closing the main stage after focus settled completed the same JavaFX harness without the exception. This isolates the observed failure to forced shutdown with a modal open in the harness. Keep accessibility enabled.
