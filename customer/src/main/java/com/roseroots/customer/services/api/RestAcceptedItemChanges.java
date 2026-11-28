package com.roseroots.customer.services.api;

import com.google.gson.JsonObject;
import com.roseroots.customer.models.Ordering;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class RestAcceptedItemChanges {
    public record Preview(Ordering.Price pricing, String fingerprint) {}

    private final SpringBootOrderService rest;

    public RestAcceptedItemChanges(SpringBootOrderService rest) { this.rest = rest; }

    public CompletionStage<Preview> preview(Ordering.Accepted order, Ordering.AcceptedLine item, Ordering.Line proposed) {
        return rest.call(path(order,item,"preview"), request(order,item,proposed), null, json ->
                new Preview(SpringBootOrderService.price(json.getAsJsonObject("pricing")), json.get("fingerprint").getAsString()));
    }

    public CompletionStage<Boolean> save(Ordering.Accepted order, Ordering.AcceptedLine item,
                                         Ordering.Line proposed, Preview review, UUID key) {
        var request = request(order,item,proposed);
        request.addProperty("fingerprint", review.fingerprint());
        return rest.call(path(order,item,"edit"), request, key, json -> updated(order,item,json));
    }

    public CompletionStage<Boolean> cancel(Ordering.Accepted order, Ordering.AcceptedLine item, UUID key) {
        return rest.call(path(order,item,"cancel"), request(order,item,null), key, json -> updated(order,item,json));
    }

    private static boolean updated(Ordering.Accepted order, Ordering.AcceptedLine item, JsonObject json) {
        if (!order.id().toString().equals(json.get("orderId").getAsString())
                || !item.id().toString().equals(json.get("itemId").getAsString())
                || json.get("itemVersion").getAsLong() != item.version()+1) {
            throw new IllegalArgumentException("Unexpected item change response");
        }
        return true;
    }

    private static String path(Ordering.Accepted order, Ordering.AcceptedLine item, String action) {
        return "sessions/"+order.sessionId()+"/orders/"+order.id()+"/items/"+item.id()+"/"+action;
    }

    private static JsonObject request(Ordering.Accepted order, Ordering.AcceptedLine item, Ordering.Line proposed) {
        var body = new JsonObject();
        body.addProperty("expectedOrderVersion", order.version());
        body.addProperty("expectedItemVersion", item.version());
        if (proposed != null) {
            var replacement = SpringBootOrderService.draftBody(new Ordering.Draft(order.sessionId(),order.mode(),
                    order.visitAllergy(), List.of(proposed))).getAsJsonArray("lines").get(0).getAsJsonObject();
            replacement.remove("clientLineId");
            replacement.remove("allergy");
            body.add("replacement", replacement);
        }
        return body;
    }
}
