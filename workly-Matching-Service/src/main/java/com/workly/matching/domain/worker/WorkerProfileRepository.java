package com.workly.matching.domain.worker;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface WorkerProfileRepository extends MongoRepository<WorkerProfile, String> {

    List<WorkerProfile> findByMobileNumberIn(List<String> mobileNumbers);

    @Query("{ 'available': true, 'skills': { $in: ?0 }, 'lastLocation': { $near: { $geometry: { type: 'Point', coordinates: [?1, ?2] }, $maxDistance: ?3 } } }")
    List<WorkerProfile> findMatchingWorkers(List<String> requiredSkills, double longitude, double latitude,
            double maxDistance, org.springframework.data.domain.Pageable pageable);

    @Query("{ 'available': true, 'skills': { $in: ?0 }, 'lastLocation': { $near: { $geometry: { type: 'Point', coordinates: [?1, ?2] }, $maxDistance: ?3 } }, 'unavailableSlots': { $not: { $elemMatch: { startTime: { $lte: ?4 }, endTime: { $gte: ?4 } } } } }")
    List<WorkerProfile> findMatchingWorkersAvailableAt(List<String> requiredSkills, double longitude, double latitude,
            double maxDistance, long targetTimeMillis, org.springframework.data.domain.Pageable pageable);

    /** Fallback: return all available workers with matching skills, ignoring location. */
    @Query("{ 'available': true, 'skills': { $in: ?0 } }")
    List<WorkerProfile> findAvailableWorkersBySkills(List<String> requiredSkills, org.springframework.data.domain.Pageable pageable);
}
