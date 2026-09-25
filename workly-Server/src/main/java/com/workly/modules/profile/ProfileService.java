package com.workly.modules.profile;

import lombok.RequiredArgsConstructor;
import com.workly.core.RegionHelper;
import com.workly.modules.notification.NotificationService;
import com.workly.modules.notification.service.UserTokenService;
import com.workly.modules.search.SearchServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final WorkerProfileRepository workerRepository;
    private final SkillSeekerProfileRepository seekerRepository;
    private final SearchServiceClient searchServiceClient;
    private final UserTokenService userTokenService;

    @Autowired
    private NotificationService notificationService;

    @CacheEvict(value = "workerProfiles", key = "#profile.mobileNumber")
    public WorkerProfile createOrUpdateWorkerProfile(WorkerProfile profile) {
        log.debug("ProfileService: [ENTER] createOrUpdateWorkerProfile - Mobile: {}", profile.getMobileNumber());
        if (profile.getSkills() != null && !profile.getSkills().isEmpty()) {
            profile.setSkills(searchServiceClient.normalizeSkills(profile.getSkills()));
        }
        WorkerProfile saved = workerRepository.save(profile);
        log.debug("ProfileService: [EXIT] createOrUpdateWorkerProfile - Synced to persistence layer.");
        return saved;
    }

    @Transactional
    public SkillSeekerProfile createOrUpdateSeekerProfile(SkillSeekerProfile profile) {
        return seekerRepository.save(profile);
    }

    @Transactional
    public SkillSeekerProfile getOrCreateSeekerProfile(String mobileNumber) {
        log.debug("ProfileService: [ENTER] getOrCreateSeekerProfile - Checking existence for mobile: {}", mobileNumber);
        return seekerRepository.findByMobileNumber(mobileNumber).orElseGet(() -> {
            log.debug("ProfileService: Profile natively absent, injecting generic base fallback profile.");
            SkillSeekerProfile profile = new SkillSeekerProfile();
            profile.setMobileNumber(mobileNumber);
            profile.setCreatedAt(java.time.LocalDateTime.now());
            return seekerRepository.save(profile);
        });
    }

    public Optional<WorkerProfile> getWorkerProfile(String mobileNumber) {
        log.debug("ProfileService: Cache MISS for workerProfile: {}", mobileNumber);
        java.util.List<WorkerProfile> profiles = workerRepository.findByMobileNumber(mobileNumber);
        if (profiles.isEmpty()) {
            return Optional.empty();
        }
        if (profiles.size() > 1) {
            WorkerProfile kept = profiles.get(0);
            for (int i = 1; i < profiles.size(); i++) {
                workerRepository.delete(profiles.get(i));
            }
            return Optional.of(kept);
        }
        return Optional.of(profiles.get(0));
    }

    public Optional<SkillSeekerProfile> getSeekerProfile(String mobileNumber) {
        return seekerRepository.findByMobileNumber(mobileNumber);
    }

    @CacheEvict(value = "workerProfiles", key = "#mobileNumber")
    public void updateLocation(String mobileNumber, double longitude, double latitude) {
        getWorkerProfile(mobileNumber).ifPresent(p -> {
            p.setLastLocation(new double[] { longitude, latitude });
            p.setRegion(RegionHelper.fromCoordinates(latitude, longitude));
            workerRepository.save(p);
        });
    }

    @CacheEvict(value = "workerProfiles", key = "#mobileNumber")
    public void updateAvailability(String mobileNumber, boolean available) {
        getWorkerProfile(mobileNumber).ifPresent(p -> {
            p.setAvailable(available);
            workerRepository.save(p);
            if (available) {
                log.debug("ProfileService: Worker {} became available — triggering re-notification scan", mobileNumber);
                notificationService.notifyNewlyAvailableWorker(p);
            }
        });
    }

    public void updateDeviceToken(String mobileNumber, String token) {
        log.debug("ProfileService: [ENTER] updateDeviceToken - Synchronizing Firebase maps.");
        userTokenService.saveToken(mobileNumber, token);

        // Try to find worker first
        Optional<WorkerProfile> worker = getWorkerProfile(mobileNumber);
        if (worker.isPresent()) {
            log.debug("ProfileService: Resolved Token update for standard WorkerProfile entity.");
            WorkerProfile p = worker.get();
            p.setDeviceToken(token);
            workerRepository.save(p);
            return;
        }

        // Else try seeker
        Optional<SkillSeekerProfile> seeker = seekerRepository.findByMobileNumber(mobileNumber);
        if (seeker.isPresent()) {
            log.debug("ProfileService: Resolved Token update for standard SeekerProfile entity.");
            SkillSeekerProfile p = seeker.get();
            p.setDeviceToken(token);
            seekerRepository.save(p);
        }
        log.debug("ProfileService: [EXIT] updateDeviceToken - Processing sequence finished.");
    }

    @Transactional
    public void addUnavailableSlot(String mobileNumber, long startTime, long endTime) {
        getWorkerProfile(mobileNumber).ifPresent(p -> {
            if (p.getUnavailableSlots() == null) {
                p.setUnavailableSlots(new java.util.ArrayList<>());
            }
            WorkerProfile.UnavailableSlot slot = new WorkerProfile.UnavailableSlot();
            slot.setStartTime(startTime);
            slot.setEndTime(endTime);
            p.getUnavailableSlots().add(slot);
            workerRepository.save(p);
        });
    }

    @Transactional
    public void removeUnavailableSlot(String mobileNumber, long startTime, long endTime) {
        getWorkerProfile(mobileNumber).ifPresent(p -> {
            if (p.getUnavailableSlots() != null) {
                p.getUnavailableSlots().removeIf(slot -> 
                    slot.getStartTime() == startTime && slot.getEndTime() == endTime);
                workerRepository.save(p);
            }
        });
    }
}
