package com.roseroots.server.ordering;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class OrderingJson {
    static final long MAX_SAFE = 9_007_199_254_740_991L;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private OrderingJson() {}

    static ObjectNode object() { return JSON.createObjectNode(); }
    static ArrayNode array() { return JSON.createArrayNode(); }

    static JsonNode parse(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw OrderingProblem.bad("Malformed ordering JSON.");
        }
    }

    static JsonNode fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) {
            throw OrderingProblem.bad("Unexpected fields in ordering request.");
        }
        for (String name : names) {
            if (!node.hasNonNull(name)) {
                throw OrderingProblem.bad("Missing field: " + name);
            }
        }
        return node;
    }

    static String text(JsonNode node, String name, int max) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.textValue().codePointCount(0, value.textValue().length()) > max
                || value.textValue().indexOf(0) >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value.textValue())) {
            throw OrderingProblem.bad("Invalid field: " + name);
        }
        return value.textValue();
    }

    static UUID id(String value) {
        if (value != null && value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            return UUID.fromString(value);
        }
        throw OrderingProblem.bad("Expected a canonical UUID.");
    }

    static UUID id(JsonNode node, String name) { return id(text(node, name, 36)); }

    static long integer(JsonNode node, String name, long min, long max) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) {
            throw OrderingProblem.bad("Invalid field: " + name);
        }
        return value.longValue();
    }

    static JsonNode list(JsonNode node, String name, int min, int max) {
        JsonNode value = node.path(name);
        if (!value.isArray() || value.size() < min || value.size() > max) {
            throw OrderingProblem.bad("Invalid array: " + name);
        }
        return value;
    }

    static String choice(JsonNode node, String name, String... choices) {
        String value = text(node, name, 40);
        if (!Set.of(choices).contains(value)) {
            throw OrderingProblem.bad("Invalid field: " + name);
        }
        return value;
    }

    static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode canonical(JsonNode value) {
        if (value.isArray()) {
            ArrayNode result = array();
            value.forEach(element -> result.add(canonical(element)));
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = object();
            var keys = new TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(value.get(key))));
            return result;
        }
        return value;
    }
}
