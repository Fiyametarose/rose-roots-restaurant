# Customer order tracking and combined bill

My Orders is available from the menu, cart and order review. Current and recent orders are grouped within the active session. The running bill sits alongside the list on landscape displays. View My Bill shows quantities, base prices, options, line totals and the server-calculated totals for each order and the session. Declined and nonbillable items remain visible in tracking and are excluded from the itemized bill. The screen uses the server order UUID, abbreviated in the heading with the full ID in its tooltip and accessible text. Order More returns to the menu within the same session.

## Data and recovery

- Customer subscribes to the existing private order-status destination, then reloads the paginated session orders and combined bill using the shared REST routes. An update triggers another REST read. WebSocket messages do not become bill or preparation data.
- The watcher reconnects with backoff, resubscribes and resynchronizes. Snapshots from replaced connections are discarded. A mismatched or inconsistent order/bill pair is retried. The screen retains the last valid snapshot while showing connection or refresh problems. Estimates are hidden during connection problems or after they expire.
- Preparation and allergy review are shown separately. The customer can read a clarification or decline message when the backend provides it. This milestone does not implement customer replies to kitchen review.
- The screen can display a fresh server-supplied remaining-time range through the view model. The current shared v1 order schema has no post-acceptance estimate field. The production adapter leaves it absent pending agreement on its location, units and validity. The existing pre-order estimate endpoint is not used for accepted orders.

## Dependencies for live use

Connect terminal authentication and authoritative session/catalog loading before enabling live submission or tracking. Verify authenticated STOMP subscription acknowledgement and the reconnect sequence against the shared backend. Agree on the post-acceptance estimate contract before reading timing data. Use the [two-PC Windows demonstration checklist](two-pc-windows-demo.md) for real order placement, kitchen updates and recovery.

The development fixtures live in test source. Visual checks display an explicit fixture label. Normal and demo application launches provide no simulated kitchen orders. Payments are outside the milestone.
