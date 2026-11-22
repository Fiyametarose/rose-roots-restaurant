# Customer integration handoff

The Customer pre-order client uses the existing draft.3 wait estimate operation in Integrated revision 40164ad548605ec960ef0a51e3dad4def49ff60c. It sends the authenticated session and existing order draft lines, without adding undocumented fields. It displays the server range until expiry and retries unavailable or failed requests after 30 seconds while the screen remains open. This revision of Integrated has the station and Chef configuration but does not expose the estimate controller.

## Decisions requested from Integrated

- Publish the ratified OpenAPI revision for Task 7. Confirm the authoritative payable balance and version, the cash request and authorized staff confirmation lifecycle, payment attempt readback and replay semantics, and the verified receipt and session closure operations. The existing Customer proposal remains the starting point. Provider availability must be authoritative so cash can proceed when external providers are disabled.
- Publish the enabled wait estimate service and agree how visit-level allergy declarations affect estimation. Draft.3 only includes item-level declarations in WaitRequest. Customer keeps the estimate unavailable when a visit-level disclosure cannot be represented.
- Publish the accepted-order estimate readback shape, hold and expiry semantics, and any workload notification. Customer currently refreshes orders by authenticated REST; the present order and bill responses do not contain an authoritative estimate.
- Agree on localized catalog and option fields or language negotiation and localized problem descriptions. Preserve identifiers across languages. The existing public branding response carries single-language text; confirm translation and fallback behavior before adding localized branding.
- Run a Windows two-PC acceptance test for live estimate changes, catalog and branding refresh, and payment recovery after a dropped connection once the compatible backend is deployed.

The previously reported Windows exception was raised by Glass Windows UI Automation during shutdown of the screenshot harness, after screenshots were saved. The recorded stack passes through WinAccessible, UiaRaiseAutomationEvent and Scene.setWindowFocused after the native view closed. Closing the modal first and then closing the main stage after focus settled completed the same JavaFX harness without the exception. This isolates the observed failure to forced shutdown with a modal open in the harness. Keep accessibility enabled.
