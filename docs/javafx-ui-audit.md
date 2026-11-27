# JavaFX UI audit and Windows rollout

The audit traced application-created dialogs from Customer, Kitchen and Manager entry points and compared the results with captured V26 desktop screens. Source coverage and visual coverage are separate. A dialog that calls the shared style helper still needs a desktop check.

| Area | Finding | Impact | Correction |
| --- | --- | --- | --- |
| Customer welcome, catalog, dish details, cart and My Orders | Mixed control treatments, especially generic inputs, toggles and dropdowns | Moderate | Shared control colors, focus, hover and disabled states |
| Customer accepted-item edit and cancel | The owner scene did not always supply the theme. The cancel prompt showed gray JavaFX controls | High | Load the Customer theme on the modal itself, mark cancellation as destructive, and size the scroll area from the owner |
| Customer Assistance, payment, recovery and all three review types | Modal composition differs across features | Moderate | Share the control and dialog styles and retain localized labels, privacy notices and Arabic direction |
| Kitchen sign-in, failed sign-in, allergy review, preparation history, Chef and Assistance reassignment, and service handoff | Native-looking JavaFX dialogs appeared next to branded application screens | High | Theme each modal from the actual owner, use a branded fallback for startup failures, retain explicit allergy and handoff instructions |
| Kitchen board, stations, ticket details, Priority Queue, handoff and History | Full order identifiers and minute counts make older tickets harder to scan. Staff and clock controls compete with the page title | High | Short display references with full-reference search retained, compact elapsed times, claimed-item context and a wrapping header. Group non-rush tickets older than 24 hours below other active tickets within each lane |
| Kitchen duty | A background refresh could ignore a click while the button appeared ready | Moderate | Disable the action during its request and give explicit save and reconnect feedback |
| Manager sign-in, dashboard, staff, station configuration, catalogs, food editing, branding, translations, orders, payments, visits and review insights | Several fields, pickers and table states retain stock treatment. Review lists fit their cards tightly | Moderate | Shared control CSS and standard content spacing for food-feedback panels |
| Manager archive, staff credentials and lifecycle, workstation revocation, photo editing, cash confirmation and cancellation, visit closure, and receipt recovery | Default dialogs or inconsistent modal styling around consequential actions | High | Explicit modal theme, branded focus and destructive treatment where required. Preserve routes, validation and cash warnings |
| Native file chooser | OS-managed interaction | Expected | Use the Windows file dialog |

The shared CSS is packaged into both JavaFX distributions. Screen-specific CSS retains each application's existing layout, accessibility labels and branding. Full identifiers remain part of operational data. Status vocabulary and access rules remain unchanged.

## Visual acceptance

Use disposable PostgreSQL 17 and trusted HTTPS to check ordering, staff sign-in, allergy approval, handoff, Assistance, accepted-item editing and cancellation, the three review types, cash confirmation and receipt recovery. Include the Manager photo editor and review controls. App dimensions below the standard window sizes help expose wrapping issues. Native Windows DPI and screen-reader checks require a separate physical desktop pass.

## Separate Windows client rollout

1. Build the published UI release with Java 21 and record its commit and package SHA-256 hashes. Stage both complete client distributions under protected local storage outside the synced worktree. Keep the deployed client packages and protected profile files available for fallback.
2. Close the four installed applications. Save restricted copies of their `installation.json` files. Update only `modules` for `Customer-Persistent01`, `Customer-Persistent02`, `Customer-Persistent03` and `Kitchen-PersistentKitchen`. Keep their existing URLs, shortcuts, trust stores and installed credentials.
3. Reopen the existing shortcuts. Verify trusted connection on all four. Check Customer review and bill readback, the Kitchen board, and Manager access against the running V26 backend. Use disposable data for workflows that would create an order, payment or review.
4. If the client check fails, close the applications and restore the prior module paths. The UI release uses the existing V26 API and database schema. Client rollback requires no server or database operation.
