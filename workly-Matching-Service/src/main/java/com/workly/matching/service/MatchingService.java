package com.workly.matching.service;

import com.workly.matching.domain.worker.WorkerProfile;
import com.workly.matching.domain.worker.WorkerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    private final WorkerProfileRepository workerRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String GEO_KEY = "worker:locations";

    public List<WorkerProfile> findMatches(List<String> requiredSkills, double longitude, double latitude,
            double radiusKm, Long scheduledTimeMillis) {
        log.debug("MatchingService: findMatches - skills: {}, lon: {}, lat: {}, radius: {}km",
                requiredSkills, longitude, latitude, radiusKm);

        // ── Hot path: Redis Geo ───────────────────────────────────────────────
        List<WorkerProfile> results = findMatchesFromRedis(requiredSkills, longitude, latitude, radiusKm,
                scheduledTimeMillis);
        if (!results.isEmpty()) {
            log.debug("MatchingService: Redis Geo hit — {} workers", results.size());
            return sortByTier(results);
        }

        // ── Cold path: MongoDB $near ──────────────────────────────────────────
        log.debug("MatchingService: Redis Geo miss — falling back to MongoDB");
        double maxDistanceMeters = radiusKm * 1000;
        List<WorkerProfile> mongoResults = (scheduledTimeMillis != null && scheduledTimeMillis > 0)
                ? workerRepository.findMatchingWorkersAvailableAt(requiredSkills, longitude, latitude,
                        maxDistanceMeters, scheduledTimeMillis)
                : workerRepository.findMatchingWorkers(requiredSkills, longitude, latitude, maxDistanceMeters);

        log.debug("MatchingService: MongoDB found {} workers", mongoResults.size());
        if (!mongoResults.isEmpty()) {
            return sortByTier(mongoResults);
        }

        // ── Last-resort path: skill-only (no geo) ────────────────────────────
        // Triggered when workers have no lastLocation stored yet (e.g. first login,
        // location push still pending the batch flush, or GPS unavailable).
        log.warn("MatchingService: No geo matches for skills={} — falling back to skill-only query", requiredSkills);
        List<WorkerProfile> skillOnlyResults = workerRepository.findAvailableWorkersBySkills(requiredSkills);
        return sortByTier(skillOnlyResults);
    }

    private List<WorkerProfile> findMatchesFromRedis(List<String> requiredSkills, double longitude, double latitude,
            double radiusKm, Long scheduledTimeMillis) {
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = redisTemplate.opsForGeo()
                    .radius(GEO_KEY, new Circle(new Point(longitude, latitude),
                            new Distance(radiusKm, Metrics.KILOMETERS)));

            if (geoResults == null || geoResults.getContent().isEmpty()) return List.of();

            List<String> nearbyMobileNumbers = geoResults.getContent().stream()
                    .map(r -> r.getContent().getName())
                    .toList();

            return workerRepository.findByMobileNumberIn(nearbyMobileNumbers).stream()
                    .filter(WorkerProfile::isAvailable)
                    .filter(p -> p.getSkills() != null
                            && requiredSkills.stream().anyMatch(s -> p.getSkills().contains(s)))
                    .filter(p -> scheduledTimeMillis == null || scheduledTimeMillis <= 0
                            || isAvailableAt(p, scheduledTimeMillis))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("MatchingService: Redis Geo query failed, falling back to MongoDB. Error: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean isAvailableAt(WorkerProfile p, long targetTimeMillis) {
        if (p.getUnavailableSlots() == null) return true;
        return p.getUnavailableSlots().stream()
                .noneMatch(s -> s.getStartTime() <= targetTimeMillis && s.getEndTime() >= targetTimeMillis);
    }

    private List<WorkerProfile> sortByTier(List<WorkerProfile> workers) {
        workers.sort(Comparator.comparing((WorkerProfile p) ->
                p.getTier() == null ? 0 : p.getTier().ordinal()).reversed());
        return workers;
    }
}
