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

## Popup inventory and acceptance status

PASS means the real packaged UI was exercised on the disposable fixture, or the named failure branch was reproduced directly. NOT TESTED means the entry was found in source and still needs a deliberate visual pass. Modal validation and keyboard checks are scoped to the actions listed.

| App | Entry point | Status | Observation |
| --- | --- | --- | --- |
| Customer | Leave visit and discard draft from the header | PASS | Switching service with a draft opened the destructive confirmation. Cancelling kept the draft |
| Customer | Advanced menu filters and clear-cart overlays | PASS | Both in-scene overlays opened. Cancelling Clear Cart preserved the draft |
| Customer | Welcome session retry overlay | NOT TESTED | Distinct failure branch with Retry and Cancel |
| Customer | Accepted item editor | PASS | Server quote, amount preview, confirmation and updated order |
| Customer | Accepted item cancellation | PASS | Destructive button, keyboard focus, Escape without submission, and a separate confirmed cancellation |
| Customer | Allergy warning in customization | PASS | Known ingredient conflict warning rendered for a disclosed allergen |
| Customer | Stale checkout quote with an allergen conflict | NOT TESTED | Distinct checkout error state |
| Customer | Dish review | PASS | Dish photo, rating, comment and saved rating |
| Customer | Order review | PASS | Rating and comment, saved against a single order |
| Customer | Restaurant review | PASS | Existing visit eligibility, submission and saved review |
| Customer | Assistance entry and request status | PASS | Modal opened, status updates followed through completion |
| Customer | Payment and issued receipt | PASS | Pending cash, Manager confirmation and verified receipt |
| Customer | Request payment cancellation and show resolution | NOT TESTED | Substates within the payment dialog |
| Customer | Receipt recovery and interrupted redemption | PASS | Original and replacement terminal recovery. English, Amharic and Arabic RTL |
| Customer | Network interruption in My Orders | PASS | Last-known order and bill, disconnected status and reconnect were visible |
| Customer | Language selector | PASS | Actual combobox dropdown opened and inspected |
| Customer | Other dropdowns and order-reference tooltips | NOT TESTED | A dedicated visual sweep remains open |
| Kitchen | Startup configuration error | PASS | Packaged warning with the shared red indicator |
| Kitchen | Staff sign-in and failed sign-in | PASS | Credential modal and actual rejected credentials |
| Kitchen | Shift duration and duty status | PASS | Start, end, automatic update and reconnect |
| Kitchen | Allergy approval | PASS | Action unavailable while held, reason prompt and confirmed approval |
| Kitchen | Allergy decline | PASS | Destructive prompt opened and cancelled on the isolated visit |
| Kitchen | Preparation history | PASS | Modal opened from a claimed item |
| Kitchen | Chef reassignment | PASS | Eligible Chef and reason controls opened and cancelled |
| Kitchen | Chef reassignment with no candidates or retrieval failure | NOT TESTED | Separate informational alert found in source |
| Kitchen | Assistance status transitions | PASS | Acknowledge, start and complete with correct staff scope |
| Kitchen | Assistance reassignment | PASS | Dedicated Waiter selection and reason modal opened and cancelled |
| Kitchen | Assistance reassignment with no available Waiter or retrieval failure | NOT TESTED | Inline failure message is a separate case |
| Kitchen | Dine-in and pickup handoff | PASS | Restricted action explanation and both confirmations |
| Kitchen | Board reconnection | PASS | Connection lost and restored after the disposable server restarted |
| Kitchen | Dropdowns and tooltips | NOT TESTED | Selection and scoped actions exercised. Individual popup and tooltip rendering still needs a dedicated pass |
| Manager | Sign-in and rejected credentials | PASS | Manager window and inline unauthorized response |
| Manager | Deactivate, archive and restore staff | PASS | Each confirmation opened and its lifecycle action persisted |
| Manager | Activate with new password | PASS | Credential modal and new authentication |
| Manager | Set password on an already active account | PASS | Credential replacement warning displayed. Cancelling left the previous credential active |
| Manager | Revoke shared workstation | PASS | Confirmation opened and cancelled |
| Manager | Station assignments | PASS | Existing Hot Kitchen assignments saved through the Manager workspace on the disposable fixture |
| Manager | Station configuration conflict response | NOT TESTED | Stale write has a separate inline error |
| Manager | Photo editor | PASS | Real editor opened from a fixture photo. Preview loading, ready images, controls and Cancel verified |
| Manager | Catalog price and availability confirmation | PASS | Availability was changed in the editor and the confirmation was cancelled |
| Manager | Catalog section visibility confirmation | NOT TESTED | Confirmation entry found in catalog workspace |
| Manager | Archive dish confirmation | PASS | Destructive prompt opened and cancelled |
| Manager | Restore dish confirmation | NOT TESTED | Distinct wording on the shared confirmation |
| Manager | Catalog form validation | PASS | Saving without a price or availability change produced an inline message |
| Manager | Failed catalog write | NOT TESTED | Server rejection message remains a separate visual case |
| Manager | Cash confirmation | PASS | Trusted HTTPS workflow with the collected-cash warning |
| Manager | Record failed collection | PASS | Reason prompt opened and cancelled during a pending cash attempt |
| Manager | Resolve Customer cancellation | NOT TESTED | Shared reason prompt with a separate action label |
| Manager | Close visit | PASS | Opened and cancelled the confirmation for a disposable visit without an outstanding balance |
| Manager | Authorize verified receipt recovery, issue and reveal result | PASS | Manager modal exercised with disposable data. Evidence captured before displaying the recovery code |
| Manager | Receipt recovery grant revocation | NOT TESTED | Inline action located inside the recovery modal |
| Manager | Review insights and detail filters | PASS | Separate restaurant and dish/order reporting and filtering |
| Manager | Date picker calendar | PASS | Opened, visually inspected and dismissed the actual calendar popup |
| Manager | Other dropdowns and tooltips | NOT TESTED | Full control-by-control visual pass remains open |
| Manager | Choose logo or dish photo | NOT TESTED | Native Windows file pickers are exempt from application styling |

### Physical Windows display coverage

| Active Windows mode | App output scale | Status | Evidence ID |
| --- | --- | --- | --- |
| 2560×1600 at 60 Hz | 150% on Customer, Kitchen and Manager | PASS | `5de48e0c`, `9bca083b` |
| 1920×1080 at 60 Hz | 125% on Customer, Kitchen and Manager | PASS | `e76138d0` |
| 1366×768 at 60 Hz | 100% on Customer, Kitchen and Manager | PASS | `3a0be3f2` |

The first run used the active physical Windows console and Windows 144 effective DPI. The two requested resolutions were applied through the Windows display driver, and the JavaFX apps reported the output scale in each configuration. Windows selected the scale for each mode. Other scale and resolution pairings remain NOT TESTED. The original display mode was restored. Logical resize screenshots are retained separately and do not count as additional OS scale settings.

The visual sweep found a generic Kitchen sign-in warning, unclear reassignment action labels, a cash-failure prompt that could be dismissed with an empty reason, and an allergy warning below the first visible portion of a compact dish page. The client fixes give these prompts action-specific wording, keep invalid reason submissions disabled and place the food-allergy warning beside the choices. The archive confirmation names the action directly.

## Release gate

Keep the client packages staged while required acceptance entries remain NOT TESTED. The live V26 backend, database and installed client profiles stay in service until a separate client rollout.

## Separate Windows client rollout

1. Build the published UI release with Java 21 and record its commit and package SHA-256 hashes. Stage both complete client distributions under protected local storage outside the synced worktree. Keep the deployed client packages and protected profile files available for fallback.
2. Close the four installed applications. Save restricted copies of their `installation.json` files. Update only `modules` for `Customer-Persistent01`, `Customer-Persistent02`, `Customer-Persistent03` and `Kitchen-PersistentKitchen`. Keep their existing URLs, shortcuts, trust stores and installed credentials.
3. Reopen the existing shortcuts. Verify trusted connection on all four. Check Customer review and bill readback, the Kitchen board, and Manager access against the running V26 backend. Use disposable data for workflows that would create an order, payment or review.
4. If the client check fails, close the applications and restore the prior module paths. The UI release uses the existing V26 API and database schema. Client rollback requires no server or database operation.
