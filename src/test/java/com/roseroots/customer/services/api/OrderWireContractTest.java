package com.roseroots.customer.services.api;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.Ordering;
import com.roseroots.customer.models.ServiceMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderWireContractTest {
    @Test void mapsLegacyOptionsExactlyLikeIntegratedOrderingPricing() {
        String dish = "00000000-0000-4000-8000-000000000099";
        String group = ConnectedCatalog.orderingOptionId(dish + "/group/", "portion");
        assertEquals("65c64d7a-c4bd-3859-b348-8f8143160f81", group);
        assertEquals("55ab7537-e193-3fdc-ab15-1943171187d6",
                ConnectedCatalog.orderingOptionId(group + "/option/", "large"));
        assertEquals(dish, ConnectedCatalog.orderingOptionId(group + "/option/", dish));
        assertThrows(IllegalArgumentException.class, () -> ConnectedCatalog.orderingOptionId(group + "/option/", ""));
    }

    @Test void conflictResponseDoesNotAuthorizeSameKeyRetry() {
        var problem = SpringBootOrderService.responseProblem(409, "application/problem+json",
                "{\"status\":409,\"code\":\"IDEMPOTENCY_CONFLICT\"}", true);
        assertEquals("IDEMPOTENCY_CONFLICT", problem.code());
        assertFalse(problem.retrySameKey());
    }
    private static JsonObject operation(String path) throws Exception {
        var document = JsonParser.parseString(Files.readString(Path.of("docs", "ordering-api-v1.openapi.json")));
        return document.getAsJsonObject().getAsJsonObject("paths").getAsJsonObject(path).getAsJsonObject("post");
    }

    private static JsonObject example(JsonObject node) {
        return node.getAsJsonObject("content").getAsJsonObject("application/json").getAsJsonObject("example");
    }

    @Test void draftPayloadMatchesSharedExample() throws Exception {
        var expected = example(operation("/order-quotes").getAsJsonObject("requestBody"));
        var item = expected.getAsJsonArray("lines").get(0).getAsJsonObject();
        var group = item.getAsJsonArray("selections").get(0).getAsJsonObject();
        var line = new Ordering.Line(UUID.fromString(item.get("clientLineId").getAsString()),
                item.get("menuItemId").getAsString(), item.get("quantity").getAsInt(),
                Map.of(group.get("groupId").getAsString(), Set.of(group.getAsJsonArray("optionIds").get(0).getAsString())),
                new AllergyDeclaration(false, Set.of(AllergyDeclaration.Allergen.MILK), "Sesame"), "Less oil");
        var draft = new Ordering.Draft(UUID.fromString(expected.get("sessionId").getAsString()), ServiceMode.DINE_IN,
                new AllergyDeclaration(true, Set.of(), ""), List.of(line));
        assertEquals(expected, SpringBootOrderService.draftBody(draft));
    }

    @Test void decodesQuoteAndAcceptedReplay() throws Exception {
        var quotes = operation("/order-quotes").getAsJsonObject("responses");
        var orders = operation("/orders").getAsJsonObject("responses");
        var quote = SpringBootOrderService.readQuote(example(quotes.getAsJsonObject("200")));
        var original = SpringBootOrderService.readAccepted(example(orders.getAsJsonObject("201")));
        var replay = SpringBootOrderService.readAccepted(example(orders.getAsJsonObject("200")));
        assertEquals(original, replay);
        assertEquals(quote.sessionId(), original.sessionId());
        assertEquals(314000, original.totals().totalMinor());
        assertEquals(Ordering.Review.PENDING_ALLERGY_REVIEW, original.lines().getFirst().review());
    }

    @Test void ambiguousResponsesKeepTheSubmissionKey() {
        assertFalse(SpringBootOrderService.responseProblem(409, "application/problem+json",
                "{\"status\":409,\"code\":\"PRICE_CHANGED\"}", true).retrySameKey());
        assertTrue(SpringBootOrderService.responseProblem(409, "application/problem+json",
                "{\"status\":409,\"code\":\"IDEMPOTENCY_IN_PROGRESS\"}", true).retrySameKey());
        assertTrue(SpringBootOrderService.responseProblem(503, "text/html", "<html>Unavailable</html>", true).retrySameKey());
    }
}
