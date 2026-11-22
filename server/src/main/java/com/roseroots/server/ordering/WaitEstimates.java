package com.roseroots.server.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.roseroots.server.ordering.OrderingJson.*;
import static com.roseroots.server.ordering.OrderingSecurity.Actor;

@Service
@Profile("catalog")
class WaitEstimates {
    private static final long HORIZON = 86400;
    private static final int MAX_WORK = 2000;
    private static final Set<String> CLEARED = Set.of("NOT_REQUIRED", "APPROVED_FOR_PREPARATION");
    private final JdbcClient jdbc;
    private final OrderingPricing pricing;
    private final OrderingService ordering;

    WaitEstimates(JdbcClient jdbc, OrderingPricing pricing, OrderingService ordering) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.ordering = ordering;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode draft(Actor actor, JsonNode body) {
        var now = Instant.now();
        var sessionId = id(body, "sessionId");
        var session = ordering.ownedSession(actor, sessionId, false);
        session.requireOpen(now);
        var lines = pricing.priceFromSnapshot(body).lines();
        if (lines.stream().anyMatch(line -> line.risk().path("unresolved").asBoolean())) return unavailable(now, "ALLERGY_REVIEW");
        var profiles = PreparationProfiles.load(jdbc, lines);
        var requested = new ArrayList<Job>();
        for (var line : lines) {
            var profile = profiles.get(line.menuItemId());
            var seconds = profile == null ? null : profile.workload(line);
            if (seconds == null) return unavailable(now, "MISSING_PREP_PROFILE");
            requested.add(new Job(profile.station(), seconds.lower(), seconds.upper(), null, true));
        }
        return estimate(now, session.expiresAt(), actor.restaurantId(), null, requested);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode accepted(Actor actor, UUID orderId) {
        var order = ordering.order(actor, orderId);
        var now = Instant.now();
        boolean outstanding = false;
        for (var line : order.path("lines")) {
            String state = line.path("preparationStatus").asText();
            if (!state.equals("RECEIVED") && !state.equals("PREPARING")) continue;
            if (!CLEARED.contains(line.path("allergyReviewStatus").asText())) return unavailable(now, "ALLERGY_REVIEW");
            outstanding = true;
        }
        return outstanding ? estimate(now, now.plusSeconds(30), actor.restaurantId(), orderId, List.of())
                : available(now, now.plusSeconds(30), 0, 0);
    }

    private ObjectNode estimate(Instant now, Instant expiration, UUID restaurant, UUID target, List<Job> requested) {
        var jobs = new ArrayList<>(jdbc.sql("""
                SELECT i.order_id, i.station_id, i.estimated_lower_seconds, i.estimated_upper_seconds,
                    i.preparation_started_at, i.preparation_status
                FROM accepted_order_item i JOIN accepted_order o ON o.id = i.order_id
                JOIN dining_session s ON s.id = o.session_id
                WHERE s.restaurant_id = :restaurant AND i.preparation_status IN ('RECEIVED','PREPARING')
                    AND i.allergy_review_status IN ('NOT_REQUIRED','APPROVED_FOR_PREPARATION')
                ORDER BY (i.preparation_status = 'PREPARING') DESC, o.rush DESC, o.sequence, i.position
                LIMIT :limit
                """).param("restaurant", restaurant).param("limit", MAX_WORK + 1).query((row, index) -> {
                    var started = row.getObject("preparation_started_at", OffsetDateTime.class);
                    var elapsed = !row.getString("preparation_status").equals("PREPARING") ? null
                            : started == null ? -1L : Duration.between(started.toInstant(), now).getSeconds();
                    return new Job(row.getObject("station_id", UUID.class), row.getObject("estimated_lower_seconds", Long.class),
                            row.getObject("estimated_upper_seconds", Long.class), elapsed,
                            row.getObject("order_id", UUID.class).equals(target));
                }).list());
        if (jobs.size() > MAX_WORK) return unavailable(now, "STALE_WORKLOAD");
        jobs.addAll(requested);
        int lastTarget = jobs.size() - 1;
        while (lastTarget >= 0 && !jobs.get(lastTarget).target() && jobs.get(lastTarget).elapsed() == null) lastTarget--;
        if (lastTarget < 0 || jobs.subList(0, lastTarget + 1).stream().noneMatch(Job::target)) {
            return unavailable(now, "STALE_WORKLOAD");
        }
        jobs.subList(lastTarget + 1, jobs.size()).clear();
        if (jobs.stream().anyMatch(job -> job.target() && !job.hasProfile())) return unavailable(now, "MISSING_PREP_PROFILE");
        if (jobs.stream().anyMatch(job -> !job.hasProfile() || job.elapsed() != null
                && (job.elapsed() < 0 || job.elapsed() >= job.upper()))) return unavailable(now, "STALE_WORKLOAD");

        var stations = new HashMap<UUID, Integer>();
        var chefs = new HashMap<UUID, Chef>();
        jdbc.sql("""
                SELECT s.id AS station, s.parallel_slots, c.id AS chef, c.available_until
                FROM kitchen_station s LEFT JOIN kitchen_station_chef a ON a.station_id = s.id
                LEFT JOIN staff_account c ON c.id = a.chef_id AND c.enabled AND c.staff_role = 'CHEF' AND c.available_until > :now
                WHERE s.active AND s.parallel_slots > 0
                """).param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)).query((row, index) -> {
                    UUID station = row.getObject("station", UUID.class);
                    UUID chef = row.getObject("chef", UUID.class);
                    stations.put(station, row.getInt("parallel_slots"));
                    if (chef != null) {
                        var until = row.getObject("available_until", OffsetDateTime.class).toInstant();
                        chefs.computeIfAbsent(chef, id -> new Chef(id, until, new HashSet<>())).stations().add(station);
                    }
                    return station;
                }).list();
        if (jobs.stream().anyMatch(job -> job.target() && (!stations.containsKey(job.station())
                || chefs.values().stream().noneMatch(chef -> chef.stations().contains(job.station()))))) {
            return unavailable(now, "NO_CAPACITY");
        }
        var low = schedule(now, jobs, stations, chefs.values().stream().toList(), false);
        var high = schedule(now, jobs, stations, chefs.values().stream().toList(), true);
        if (low == null || high == null) return unavailable(now, "STALE_WORKLOAD");
        Instant expiry = now.plusSeconds(30);
        if (expiration.isBefore(expiry)) expiry = expiration;
        for (Chef chef : chefs.values()) if (chef.until().isBefore(expiry)) expiry = chef.until();
        if (!expiry.isAfter(now)) return unavailable(now, "STALE_WORKLOAD");
        return available(now, expiry, Math.min(low, high), Math.max(low, high));
    }

    private record Job(UUID station, Long lower, Long upper, Long elapsed, boolean target) {
        boolean hasProfile() { return station != null && lower != null && upper != null; }
    }
    private record Chef(UUID id, Instant until, Set<UUID> stations) {}
    private record Slot(Chef chef, int lane, long start) {}

    private static Long schedule(Instant now, List<Job> jobs, Map<UUID, Integer> capacity, List<Chef> chefs, boolean upper) {
        var lanes = new HashMap<UUID, long[]>();
        capacity.forEach((id, size) -> lanes.put(id, new long[size]));
        var lastChefUse = new HashMap<UUID, Long>();
        long completion = 0;
        Comparator<Slot> preference = Comparator.comparingLong(Slot::start)
                .thenComparingInt(slot -> slot.chef().stations().size()).thenComparing(slot -> slot.chef().id());
        for (Job job : jobs) {
            // Later queued work cannot delay an earlier target. In-progress work is always loaded first.
            long elapsed = job.elapsed() == null ? 0 : job.elapsed();
            long seconds = Math.max(1, (upper ? job.upper() : job.lower()) - elapsed);
            long[] station = lanes.get(job.station());
            if (station == null) return null;
            int lane = 0;
            for (int i = 1; i < station.length; i++) if (station[i] < station[lane]) lane = i;
            int bestLane = lane;
            var slot = chefs.stream().filter(chef -> chef.stations().contains(job.station()))
                    .map(chef -> new Slot(chef, bestLane, Math.max(station[bestLane], lastChefUse.getOrDefault(chef.id(), 0L))))
                    .filter(choice -> job.elapsed() == null || choice.start() == 0)
                    .filter(choice -> choice.start() + seconds <= HORIZON
                            && !now.plusSeconds(choice.start() + seconds).isAfter(choice.chef().until()))
                    .min(preference).orElse(null);
            if (slot == null) return null;
            long finish = slot.start() + seconds;
            station[slot.lane()] = finish;
            lastChefUse.put(slot.chef().id(), finish);
            if (job.target()) completion = Math.max(completion, finish);
        }
        return completion;
    }

    private static ObjectNode available(Instant now, Instant expiry, long lower, long upper) {
        return object().put("state", "AVAILABLE").put("minimumMinutes", lower / 60).put("maximumMinutes", (upper + 59) / 60)
                .put("calculatedAt", now.toString()).put("validUntil", expiry.toString());
    }

    private static ObjectNode unavailable(Instant now, String reason) {
        return object().put("state", "UNAVAILABLE").put("reason", reason).put("calculatedAt", now.toString());
    }
}
