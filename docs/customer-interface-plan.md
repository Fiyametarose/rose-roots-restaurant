# Customer interface inventory and plan

The ten supplied PNGs and the offline gallery describe one landscape touchscreen journey. Welcome uses a food photograph, botanical artwork and large service choices. Most ordering screens share an evergreen category rail, a cream content area, a top bar with table or pickup context and customer actions, and a dark primary action. Gold outlines, serif headings, rounded controls and food photography repeat across the designs. Keep the customer session visible as content changes and keep focus and status legible without relying on color alone.

| Reference | Design observed | Implementation |
| --- | --- | --- |
| 01 Welcome | Rose mark, culinary statement, Dine In and Takeaway cards, four language choices | Implemented with JavaFX controls and bundled restaurant settings. Both choices route to the menu destination. Future Spring Boot session creation will resolve the assigned table or issue the pickup reference. |
| 02 Main Menu | Category rail, cuisine chips, vegetarian switch, photo cards and bottom cart bar | The shared shell, photo grid, search, sorting and structured filters use the local catalog. The header shows an empty cart indicator pending cart implementation. |
| 03 Drinks | Hot, Cold and Shakes subtabs with the same card grid and cart bar | Subtabs render as catalog children, so a newly supplied subtype appears on refresh. |
| 04 Customize | Photo and dish overview beside quantity, spice, side, notes and add-to-cart | Dish Details shows the photo, price, availability, ingredients, dietary labels, allergens, possible cross-contact and optional preparation duration. Customization controls and the server readiness range are future work. |
| 05 Cart | Editable lines, quantities, service context and totals | Keep separate line configurations. Refresh the quote, show the estimate and confirm table/pickup and Place Order before billing. |
| 06 My Orders | Current and recent orders, progress, running bill, Order More and View Bill | Render separate order rounds and item-level states. Reconcile WebSocket signals from REST and preserve the dining session while subsequent carts are placed. |
| 07 Assistance | Touch request choices, optional other text and send | Use a shared panel accessible during ordering. Preserve the cart and track acknowledgment, progress and completion. |
| 08 Payment | Method choices and payable summary | Load the combined bill first. Support server-confirmed simulation and the counter flow, which remains unpaid until staff record collection. |
| 09 Receipt | Payment success, receipt lines, reference and review action | Render paid evidence from Spring Boot, including every order round, timestamp and payment reference. Add receipt export and print support. |
| 10 Review | Overall and topic stars, feedback and order context | Build an optional review form with overall rating, supported topic tags, optional text and a thank-you confirmation. |

## Functionality the visuals only partially cover

- Confirm the assigned table and active session for Dine In. Create a backend pickup reference and packing instruction for Takeaway. The terminal stays fixed to the table.
- Add query search, verified vegetarian, vegan, fasting-friendly and Gluten-Free filters, allergen information and optional exclusions. The mockups mainly show a vegetarian switch. Unavailable dishes need clear status and disabled order actions.
- Show a pre-order ready-time range in Details and Checkout, refresh it when the proposal changes and immediately before confirmation, and show an unavailable state when reliable data is missing. Spring Boot accounts for active chefs, station queues, preparation times, batch quantities and Rush precedence.
- Include a distinct Place Order confirmation and prevent duplicate submissions with an idempotency key. Payment follows the session bill. The cart mockup leads directly to payment and omits this distinction.
- Show item-level progress, served or collected states, staff-approved cancellation rules, elapsed time and connectivity feedback. Keep assistance request state and protect against repeated pending requests.
- Include a distinct combined bill for all accepted order rounds, historical price snapshots, configured charges and multiple payment states. Issue a paid receipt only after confirmation. Keep reviews optional and session-linked.
- Treat language choices as provisional. The visual and earlier specification list different sets. Branding, supported locales, catalog data, fees and currency come from public settings and catalog services.

## Implementation sequence

1. Welcome and menu handoff. Locale and service mode live in customer state. Welcome, menu and dish details use the existing route registry.
2. Catalog vertical slice. The shared shell supplies the rail and header. Food cards and photo components serve every category. The asynchronous service boundary supports a mock snapshot now and Spring Boot categories/items later. Menu refresh replaces the snapshot, reconciles removed categories and reapplies the active filters.
3. Order vertical slice. Share action styling, quantity controls, option groups, validation and form layout between details and checkout. Add a reusable confirmation dialog with keyboard focus handling when order confirmation is wired. Obtain authoritative timing, totals, accepted orders and table/pickup IDs through service interfaces.
4. Session services. Build tracking, assistance, combined bill, payment, receipt and review on the same shell and forms. Give each route loading, empty and error states. Preserve locale and active session during navigation. Add tests for catalog filtering, order retries, ETA refresh, multiple order rounds and payment reconciliation as those contracts become available.

## Task 2 design comparison

- The Main Courses screen uses the reference's forest-green rail, cream surface, gold accents, serif title, category highlight and three photo columns. Search, sorting, refresh and collapsible advanced filters occupy extra space above the grid. The rail uses a straight edge and compact text navigation. At smaller sizes the grid changes to two or one column.
- Drinks shares the food-grid layout. Its four tabs include **All drinks**, plus the three reference subcategories.
- Dish Details keeps a large photo and overview next to an information panel. The panel shows food information, including sample preparation duration. Customization and Add to Cart are planned for the next milestone.
- Assistance opens an information dialog. Requests, the bottom checkout bar and real terminal-session assignments are pending.
- The 13 standalone demo images are under `src/main/resources/images/menu`. Main-course and drink photos are cropped from the supplied references. Four starter and dessert photos were generated with the built-in image tool from a prompt for a realistic 2-by-2 contact sheet featuring lentil sambusa, black-eyed pea salad, raspberry cheesecake and tropical fruit in warm restaurant lighting.

Image generation prompt

> Create one realistic food photography contact sheet for a restaurant demo, four perfectly aligned equal rectangular photos in a 2 by 2 grid, no gaps or margins, no text, labels or graphics. Upper left Ethiopian triangular lentil sambusa pastries with a small green herb dip; upper right South Sudanese-style black-eyed pea salad with diced tomatoes and herbs; lower left international vanilla cheesecake slice with raspberries; lower right South Sudanese-style sliced mango, pineapple and orange fruit plate. Independent centered subjects within each quadrant, warm natural light, rustic dark wood tables, evergreen hints, appetizing upscale restaurant photography.

## Session and menu refinement

### Allergy disclosure in customization and checkout

Dish Details shows ingredients, catalog dietary labels, known allergens and possible cross-contact. Empty allergen fields indicate missing entries, and incomplete catalog data retains an inline warning.

In the customization and cart milestone, add a compact, touch-friendly Food Allergies & Special Instructions form in the botanical theme. Support multiple allergens, an explicit “No known allergies to declare” choice, and optional free text for additional allergies and preparation requests. Retain disclosures within the dining session and attach the relevant information to each order item. Free text is a request and does not change verified ingredients. Compare selected allergens against structured ingredient allergen and possible cross-contact data. Show which kind of match was found and require an appropriate safety review before submission can proceed. Ordinary customization should stay within the ordering flow.

Show the disclosures in the final summary, require a fresh review before each order round, and include clearly highlighted disclosures in the kitchen order data. Add tests for multiple selections and the explicit no-known-allergies choice, direct ingredient matches, cross-contact matches, unresolved safety review, and checkout confirmation resetting for each round.

Menu and Details share a persistent cart summary and a separate Back to Welcome control. The mock catalog now has 19 items, including six mains and four starters, using standalone images. Advanced filters are in a compact overlay, so their open or closed state does not reflow the grid. The reference checkout bar is represented by draft review. The detail panel presents catalog information and a draft add action. Customization is a later milestone.

`DiningSessionService` resolves an administrator-provisioned terminal and opens or resumes a visit by service mode. The Spring Boot boundary accepts neither a client-chosen table ID nor a session ID. The explicit demo fixture supports Table 07 and a separate takeaway pickup namespace. The demo provider keeps open visits in memory and returns a new ID after staff close. The visit model rejects order references from another visit and can sum server-confirmed amounts independently of the draft.

The six new demo photos were generated with the built-in image tool and saved in `src/main/resources/images/menu`. The prompt was:

> One photorealistic upscale restaurant food photography contact sheet, 3 columns by 2 rows, six perfectly rectangular equal separate photographs edge to edge without gaps, margins or text. Top row left to right: Ethiopian beef tibs, seared small beef pieces with peppers in a dark bowl; South Sudanese groundnut vegetable stew with rice on side; international herb chicken with bright roast vegetables. Bottom row: Ethiopian azifa lentil salad with green herbs; international tomato bruschetta on toasted bread; international dark chocolate mousse in small glass with raspberries. Warm wood backdrop, deep green accents, natural light, centered individual dishes in each of six cells with balanced composition for landscape cards. No logos or writing.
