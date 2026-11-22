# Chef shifts and stations

In Manager **Kitchen Configuration**, use **Stations & assignments** to edit a station's active state, parallel slots and Chef assignments. **Save station** saves those settings together. The station card shows saved capacity and how many assigned Chefs are on duty.

In **Chef shifts**, choose a 4-, 8-, or 12-hour shift and select **Start Shift**. Eight hours is the default. The card shows the server's shift end time in the workstation's local time zone. **End Shift** ends availability immediately on the server. The display changes to **Off Duty** when a shift expires. Deactivated and archived staff cannot start shifts.

An unfinished item stays with its current Chef after the shift ends. Kitchen Order Details flags unavailable owners, and a Supervisor can use **Reassign item** to transfer the work to an eligible Chef. Claims, preparation and estimated capacity use current availability. Dish-level preparation settings remain in Add Dish and Edit Dish.

The existing Manager `GET/PATCH /api/v1/admin/chef-availability` routes, `minutes` and `expectedVersion` inputs, and audit behavior are preserved. Chef responses now also include `archived`, `eligible`, `unfinishedItems` and `serverTime`. Kitchen ticket owner metadata includes optional `onDuty`. A stale version requires refresh. No database migration is needed.
