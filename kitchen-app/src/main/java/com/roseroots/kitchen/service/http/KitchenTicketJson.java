package com.roseroots.kitchen.service.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roseroots.kitchen.model.OrderCompletion;
import com.roseroots.kitchen.model.Ticket;
import com.roseroots.kitchen.model.Ticket.Item;
import com.roseroots.kitchen.model.Ticket.ServerItem;
import com.roseroots.kitchen.model.Ticket.ServerOrder;
import com.roseroots.kitchen.model.Ticket.Status;
import com.roseroots.kitchen.model.Ticket.Type;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

final class KitchenTicketJson {
    record Page(List<Ticket> tickets, String nextCursor) {}

    private final ToLongFunction<UUID> handle;

    KitchenTicketJson(ToLongFunction<UUID> handle) {
        this.handle = handle;
    }

    Page readPage(String body) {
        JsonObject page = object(body);
        var tickets = new ArrayList<Ticket>();
        for (JsonElement entry : page.getAsJsonArray("tickets")) {
            tickets.add(ticket(entry.getAsJsonObject()));
        }
        JsonElement cursor = page.get("nextCursor");
        return new Page(List.copyOf(tickets), cursor.isJsonNull() ? null : text(page, "nextCursor"));
    }

    Ticket ticket(JsonObject wrapper) {
        JsonObject order = wrapper.getAsJsonObject("order");
        UUID id = id(order, "id");
        Status status = Status.valueOf(text(order, "status"));
        var stationCodes = wrapper.getAsJsonObject("stationCodes");
        var itemChefs = wrapper.getAsJsonObject("itemChefs");
        var items = new ArrayList<Item>();
        for (JsonElement line : order.getAsJsonArray("lines")) {
            items.add(item(line.getAsJsonObject(), stationCodes, itemChefs));
        }
        if (items.stream().map(line -> line.server().id()).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Duplicate item");
        }
        if (status == Status.READY && (items.stream().noneMatch(line -> line.status() == Status.READY)
                || items.stream().anyMatch(line -> line.status() != Status.READY && line.status() != Status.CANCELLED))) {
            throw new IllegalArgumentException("Inconsistent ready state");
        }
        boolean rush = switch (text(wrapper, "priority")) {
            case "RUSH" -> true;
            case "NORMAL" -> false;
            default -> throw new IllegalArgumentException("Unknown priority");
        };
        var handoff = wrapper.getAsJsonObject("handoff");
        var audit = handoff == null ? null : new Ticket.HandoffAudit(
                handoff.get("actor").isJsonNull() ? null : text(handoff, "actor"),
                Status.valueOf(text(handoff, "status")), OffsetDateTime.parse(text(handoff, "completedAt")));
        var server = new ServerOrder(id, id(order, "sessionId"), version(order), status,
                rush, bool(wrapper, "delayed"), declaration(order.getAsJsonObject("visitAllergy")), audit);
        var completed = wrapper.get("completedAt");
        var completion = completed == null ? null : new OrderCompletion(OrderCompletion.Status.valueOf(status.name()),
                completed.isJsonNull() ? null : OffsetDateTime.parse(completed.getAsString()));
        return new Ticket(handle.applyAsLong(id), Type.valueOf(text(order, "mode")),
                text(wrapper, "serviceDestination"), OffsetDateTime.parse(text(order, "acceptedAt")),
                null, List.of(), items, completion, server);
    }

    private Item item(JsonObject line, JsonObject stationCodes, JsonObject itemChefs) {
        UUID id = id(line, "id");
        var chef = itemChefs == null ? null : itemChefs.getAsJsonObject(id.toString());
        var station = stationCodes == null || !stationCodes.has(id.toString()) ? null
                : Ticket.Station.valueOf(text(stationCodes, id.toString()));
        JsonObject pricing = line.getAsJsonObject("pricing");
        JsonObject risk = line.getAsJsonObject("allergyRisk");
        String options = pricing.getAsJsonArray("options").asList().stream()
                .map(option -> text(option.getAsJsonObject(), "name")).collect(Collectors.joining(" · "));
        String ingredientMatches = arrayText(risk, "ingredientMatches");
        String disclosure = declaration(line.getAsJsonObject("allergy"));
        String allergens = ingredientMatches.isBlank() ? disclosure
                : disclosure.isBlank() ? ingredientMatches : ingredientMatches + " · " + disclosure;
        String reviewStatus = text(line, "allergyReviewStatus");
        switch (reviewStatus) {
            case "NOT_REQUIRED", "PENDING_ALLERGY_REVIEW", "CLARIFICATION_REQUESTED", "DEFERRED",
                    "CUSTOMER_APPROVAL_REQUIRED", "APPROVED_FOR_PREPARATION", "DECLINED" -> {}
            default -> throw new IllegalArgumentException("Unknown allergy review status");
        }
        int quantity = Math.toIntExact(integer(pricing, "quantity"));
        if (quantity < 1 || quantity > 99) {
            throw new IllegalArgumentException("Invalid quantity");
        }
        return new Item(handle.applyAsLong(id), text(pricing, "name"), quantity, station, options,
                text(line, "preparationInstructions"), allergens, arrayText(risk, "crossContactMatches"),
                Status.valueOf(text(line, "preparationStatus")),
                new ServerItem(id, version(line), reviewStatus, bool(risk, "unresolved"),
                        chef == null ? null : id(chef, "id"), chef == null ? null : text(chef, "username")));
    }

    private static String declaration(JsonObject declaration) {
        String tags = arrayText(declaration, "allergens");
        String note = text(declaration, "other");
        return tags.isBlank() ? note : note.isBlank() ? tags : tags + " · " + note;
    }

    static JsonObject object(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static String arrayText(JsonObject element, String key) {
        return element.getAsJsonArray(key).asList().stream()
                .map(JsonElement::getAsString).collect(Collectors.joining(", "));
    }

    private static boolean bool(JsonObject element, String key) {
        var value = element.getAsJsonPrimitive(key);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("Invalid boolean");
        }
        return value.getAsBoolean();
    }

    private static String text(JsonObject element, String key) {
        var value = element.getAsJsonPrimitive(key);
        if (!value.isString()) {
            throw new IllegalArgumentException("Invalid string");
        }
        return value.getAsString();
    }

    private static UUID id(JsonObject element, String key) {
        String value = text(element, key);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("Invalid canonical ID");
        }
        return id;
    }

    private static long integer(JsonObject element, String key) {
        var value = element.getAsJsonPrimitive(key);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Invalid number");
        }
        return value.getAsBigDecimal().longValueExact();
    }

    private static long version(JsonObject element) {
        long version = integer(element, "version");
        if (version < 1 || version > 9007199254740991L) {
            throw new IllegalArgumentException("Invalid version");
        }
        return version;
    }
}
