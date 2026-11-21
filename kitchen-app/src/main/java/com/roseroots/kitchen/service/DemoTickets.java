package com.roseroots.kitchen.service;

import com.roseroots.kitchen.model.RushChange;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.Station;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

public final class DemoTickets {
    private DemoTickets() {}

    public static List<Ticket> create(OffsetDateTime now) {
        return List.of(
                ticket(now, 1023, Type.DINE_IN, "Table 05", 18, true,
                        new Item(1, "Doro Wat", 2, Station.HOT, "Medium spice · extra injera",
                                "Serve sauce on the side", "Egg", "Shared prep surface also handles wheat", Status.RECEIVED),
                        new Item(2, "Hibiscus Tea", 1, Station.BEVERAGES, "Unsweetened",
                                "", "", "", Status.RECEIVED)),
                ticket(now, 1024, Type.DINE_IN, "Table 08", 16, false,
                        new Item(3, "Beef Tibs", 1, Station.HOT, "Mild spice", "No onions", "", "", Status.RECEIVED)),
                ticket(now, 1026, Type.TAKEAWAY, "Pickup P-026", 2, false,
                        new Item(4, "Azifa", 2, Station.COLD, "Lemon dressing", "", "", "", Status.RECEIVED)),
                ticket(now, 1019, Type.DINE_IN, "Table 03", 12, false,
                        new Item(5, "Kisra with Bamia", 2, Station.HOT, "Extra kisra", "", "", "", Status.PREPARING)),
                ticket(now, 1020, Type.TAKEAWAY, "Pickup P-020", 8, false,
                        new Item(6, "Groundnut Stew", 1, Station.HOT, "Side of rice",
                                "", "Peanuts", "", Status.PREPARING),
                        new Item(7, "Mango Juice", 2, Station.BEVERAGES, "No ice", "", "", "", Status.READY)),
                ticket(now, 1017, Type.DINE_IN, "Table 02", 14, false,
                        new Item(8, "Grilled Fish", 2, Station.HOT, "Lemon and herbs", "", "Fish", "", Status.READY)),
                ticket(now, 1018, Type.TAKEAWAY, "Pickup P-018", 10, false,
                        new Item(9, "Lentil Sambusa", 3, Station.HOT, "Tamarind dip", "", "Wheat", "", Status.READY)));
    }

    private static Ticket ticket(OffsetDateTime now, long id, Type type, String destination,
                                 int minutes, boolean rush, Item... items) {
        return new Ticket(id, type, destination, now.minusMinutes(minutes), Duration.ofMinutes(15),
                rush ? List.of(new RushChange(id, RushChange.Action.APPLY_RUSH, "Incorrect dish replacement",
                        MockRushAuthorization.SUPERVISOR, now.minusMinutes(3))) : List.of(), List.of(items));
    }
}
