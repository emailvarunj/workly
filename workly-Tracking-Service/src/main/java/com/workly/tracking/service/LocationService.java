package com.workly.tracking.service;

import com.workly.tracking.domain.worker.WorkerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final WorkerProfileRepository workerProfileRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String GEO_KEY = "worker:locations";
    private static final String DIRTY_SET_KEY = "worker:locations:dirty";

    /**
     * Hot path: write location to Redis Geo (sub-ms latency).
     * The worker mobile is tracked in a dirty set for later batch-flush to MongoDB.
     */
    public void updateWorkerLocation(String mobileNumber, double longitude, double latitude) {
        log.debug("LocationService: updateWorkerLocation - mobile: {}, lon: {}, lat: {}", mobileNumber, longitude, latitude);
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            log.warn("LocationService: Invalid coordinates - lon: {}, lat: {}", longitude, latitude);
            throw new IllegalArgumentException(
                    "Invalid coordinates: latitude must be [-90,90] and longitude must be [-180,180]");
        }

        redisTemplate.opsForGeo().add(GEO_KEY,
                new org.springframework.data.geo.Point(longitude, latitude), mobileNumber);
        redisTemplate.opsForSet().add(DIRTY_SET_KEY, mobileNumber);
        log.debug("LocationService: Written to Redis Geo + dirty set");
    }

    /**
     * Batch-flush dirty locations from Redis Geo to MongoDB every 60 seconds.
     * Reduces MongoDB write pressure by ~60x compared to direct writes.
     */
    @Scheduled(fixedDelay = 60_000)
    public void flushLocationsToMongo() {
        // Atomically hand off the dirty set to a private key before processing it, so a
        // SADD landing between "read members" and "delete" (previously: SMEMBERS then
        // unconditional DEL) lands on a fresh DIRTY_SET_KEY instead of being dropped.
        String flushingKey = DIRTY_SET_KEY + ":flushing:" + System.currentTimeMillis();
        try {
            redisTemplate.rename(DIRTY_SET_KEY, flushingKey);
        } catch (Exception e) {
            // DIRTY_SET_KEY doesn't exist — nothing dirty since the last flush.
            return;
        }

        Set<String> dirtyWorkers = redisTemplate.opsForSet().members(flushingKey);
        if (dirtyWorkers == null || dirtyWorkers.isEmpty()) {
            redisTemplate.delete(flushingKey);
            return;
        }

        log.debug("LocationService: Flushing {} dirty locations to MongoDB", dirtyWorkers.size());

        // Batch-load all dirty profiles in one query instead of one per worker
        java.util.List<String> mobileList = new java.util.ArrayList<>(dirtyWorkers);
        java.util.Map<String, com.workly.tracking.domain.worker.WorkerProfile> profileMap =
                workerProfileRepository.findByMobileNumberIn(mobileList).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.workly.tracking.domain.worker.WorkerProfile::getMobileNumber,
                                p -> p));

        int flushed = 0;
        java.util.List<com.workly.tracking.domain.worker.WorkerProfile> toSave = new java.util.ArrayList<>();
        for (String mobile : mobileList) {
            try {
                var positions = redisTemplate.opsForGeo().position(GEO_KEY, mobile);
                if (positions != null && !positions.isEmpty() && positions.get(0) != null) {
                    var point = positions.get(0);
                    com.workly.tracking.domain.worker.WorkerProfile profile = profileMap.get(mobile);
                    if (profile != null) {
                        profile.setLastLocation(new double[]{ point.getX(), point.getY() });
                        toSave.add(profile);
                        flushed++;
                    }
                }
            } catch (Exception e) {
                log.error("LocationService: Failed to read Redis position for {}", mobile, e);
            }
        }
        if (!toSave.isEmpty()) {
            workerProfileRepository.saveAll(toSave);
        }

        redisTemplate.delete(flushingKey);
        log.info("LocationService: Flushed {}/{} locations to MongoDB", flushed, dirtyWorkers.size());
    }
}
