package com.workly.modules.job;

import com.workly.core.RegionHelper;
import com.workly.core.WorklyException;
import com.workly.modules.job.outbox.OutboxEvent;
import com.workly.modules.job.outbox.OutboxEventRepository;
import com.workly.modules.profile.ProfileService;
import com.workly.modules.search.SearchServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobService {

    private final JobRepository jobRepository;
    private final JobAcceptanceService jobAcceptanceService;
    private final OutboxEventRepository outboxEventRepository;
    private final SearchServiceClient searchServiceClient;
    private final ProfileService profileService;
    private final MongoTemplate mongoTemplate;

    @Qualifier("secondaryMongoTemplate")
    private final MongoTemplate secondaryMongoTemplate;

    private static final String JOB_TOPIC = "job.created";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Map<JobStatus, Set<JobStatus>> VALID_TRANSITIONS = Map.of(
            JobStatus.CREATED, Set.of(JobStatus.BROADCASTED, JobStatus.SCHEDULED, JobStatus.CANCELLED),
            JobStatus.SCHEDULED, Set.of(JobStatus.BROADCASTED, JobStatus.CANCELLED),
            JobStatus.BROADCASTED,
            Set.of(JobStatus.ASSIGNED, JobStatus.PENDING_ACCEPTANCE, JobStatus.CANCELLED, JobStatus.EXPIRED),
            JobStatus.PENDING_ACCEPTANCE, Set.of(JobStatus.ASSIGNED, JobStatus.CANCELLED),
            JobStatus.ASSIGNED, Set.of(JobStatus.COMPLETED, JobStatus.CANCELLED));

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "availableJobs", allEntries = true)
    })
    public Job createJob(Job job) {
        log.debug("JobService: [ENTER] createJob - Transaction context initiated for job parameter.");
        if (job.getRequiredSkills() != null && !job.getRequiredSkills().isEmpty()) {
            job.setRequiredSkills(searchServiceClient.normalizeSkills(job.getRequiredSkills()));
        }

        job.setStatus(JobStatus.CREATED);
        if (job.isImmediate()) {
            job.setStatus(JobStatus.BROADCASTED);
        } else if (job.getScheduledTime() != null) {
            job.setStatus(JobStatus.SCHEDULED);
        }
        if (job.getAssignmentMode() == AssignmentMode.MANUAL_SELECT
                && job.getWorkerMobileNumber() != null
                && !job.getWorkerMobileNumber().isBlank()) {
            job.setStatus(JobStatus.PENDING_ACCEPTANCE);
        }

        if (job.getLocation() != null) {
            job.setRegion(RegionHelper.fromLocation(job.getLocation()));
        }

        log.debug("JobService: Binding secure completion OTP code to Job envelope.");
        job.setCompletionOtp(String.format("%04d", SECURE_RANDOM.nextInt(10000)));

        Job savedJob = jobRepository.save(job);
        log.debug("JobService: Flushed Job {} entirely to Postgres.", savedJob.getId());

        JobEvent event = JobEvent.builder()
                .jobId(savedJob.getId())
                .eventType("JOB_CREATED")
                .status(savedJob.getStatus())
                .build();
        saveOutboxEvent(JOB_TOPIC, event);

        log.debug("JobService: [EXIT] createJob - Kafka topic queued via outbox pattern.");
        return savedJob;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "availableJobs", allEntries = true),
        @CacheEvict(value = "seekerJobs",    allEntries = true)
    })
    public Job updateJobStatus(String jobId, JobStatus status, String requestingUserMobile) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> WorklyException.notFound("Job not found"));

        if (!job.getSeekerMobileNumber().equals(requestingUserMobile)) {
            throw WorklyException.forbidden("You are not authorized to update this job");
        }

        Set<JobStatus> allowedStatuses = VALID_TRANSITIONS.get(job.getStatus());
        if (allowedStatuses == null || !allowedStatuses.contains(status)) {
            throw WorklyException.badRequest(
                    String.format("Cannot transition from %s to %s", job.getStatus(), status));
        }

        if (status == JobStatus.CANCELLED && job.getStatus() == JobStatus.ASSIGNED && job.getScheduledTime() != null) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            if (now.plusHours(2).isAfter(job.getScheduledTime()) || now.isAfter(job.getScheduledTime())) {
                job.setPenaltyAmount(15.00);
                job.setCancellationReason("LATE_CANCELLATION");
                log.info("JobService: Applied late cancellation penalty for job {}", jobId);
            }
            // Free the worker's schedule
            long startTime = job.getScheduledTime().minusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endTime = job.getScheduledTime().plusHours(2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            profileService.removeUnavailableSlot(job.getWorkerMobileNumber(), startTime, endTime);
        }

        job.setStatus(status);
        Job savedJob = jobRepository.save(job);

        JobEvent event = JobEvent.builder()
                .jobId(savedJob.getId())
                .eventType("JOB_STATUS_UPDATED")
                .status(savedJob.getStatus())
                .workerId(savedJob.getWorkerMobileNumber())
                .build();
        saveOutboxEvent("job.status.updated", event);

        return savedJob;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "availableJobs", allEntries = true),
        @CacheEvict(value = "seekerJobs",    allEntries = true)
    })
    public Job updateJob(String jobId, Job jobDetails, String requestingUserMobile) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> WorklyException.notFound("Job not found"));

        if (!job.getSeekerMobileNumber().equals(requestingUserMobile)) {
            throw WorklyException.forbidden("You are not authorized to update this job");
        }

        if (jobDetails.getScheduledTime() != null && job.getStatus() == JobStatus.ASSIGNED) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            if (job.getScheduledTime() != null && (now.plusHours(2).isAfter(job.getScheduledTime()) || now.isAfter(job.getScheduledTime()))) {
                job.setPenaltyAmount(10.00);
                job.setCancellationReason("LATE_RESCHEDULE");
                log.info("JobService: Applied late reschedule penalty for job {}", jobId);
            }
            // Release old slot
            if (job.getScheduledTime() != null) {
                long oldStartTime = job.getScheduledTime().minusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                long oldEndTime = job.getScheduledTime().plusHours(2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                profileService.removeUnavailableSlot(job.getWorkerMobileNumber(), oldStartTime, oldEndTime);
            }
            // Add new slot
            long newStartTime = jobDetails.getScheduledTime().minusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long newEndTime = jobDetails.getScheduledTime().plusHours(2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            profileService.addUnavailableSlot(job.getWorkerMobileNumber(), newStartTime, newEndTime);
        }

        if (jobDetails.getScheduledTime() != null) {
            job.setScheduledTime(jobDetails.getScheduledTime());
            job.setStatus(JobStatus.SCHEDULED); // Ensure status is SCHEDULED if time is updated
        }

        Job savedJob = jobRepository.save(job);

        JobEvent event = JobEvent.builder()
                .jobId(savedJob.getId())
                .eventType("JOB_UPDATED")
                .status(savedJob.getStatus())
                .build();

        saveOutboxEvent(JOB_TOPIC, event);

        return savedJob;
    }

    private void saveOutboxEvent(String topic, Object payload) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setTopic(topic);
        outboxEvent.setPayload(payload);
        outboxEventRepository.save(outboxEvent);
    }

    public List<Job> getSeekerJobs(String mobileNumber, String type) {
        return getSeekerJobs(mobileNumber, type, 0, 50);
    }

    @Cacheable(value = "seekerJobs", key = "#mobileNumber + ':' + #type + ':' + #page + ':' + #size",
               unless = "#result.isEmpty()")
    public List<Job> getSeekerJobs(String mobileNumber, String type, int page, int size) {
        log.debug("JobService: [ENTER] getSeekerJobs - mobile: {}, type: {}, page: {}, size: {}", mobileNumber, type, page, size);

        List<JobStatus> statuses = resolveStatusFilter(type);
        Query q = (statuses != null)
                ? Query.query(Criteria.where("seekerMobileNumber").is(mobileNumber)
                        .and("status").in(statuses))
                : Query.query(Criteria.where("seekerMobileNumber").is(mobileNumber));
        q.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        q.with(org.springframework.data.domain.PageRequest.of(page, size));

        List<Job> jobs = mongoTemplate.find(q, Job.class);
        log.info("Found {} {} jobs for seeker mobile: {}", jobs.size(), type != null ? type : "all", mobileNumber);
        log.debug("JobService: [EXIT] getSeekerJobs - collection size: {}", jobs.size());
        return jobs;
    }

    private List<JobStatus> resolveStatusFilter(String type) {
        if ("active".equalsIgnoreCase(type)) {
            return List.of(JobStatus.CREATED, JobStatus.BROADCASTED, JobStatus.SCHEDULED,
                    JobStatus.PENDING_ACCEPTANCE, JobStatus.ASSIGNED);
        } else if ("past".equalsIgnoreCase(type)) {
            return List.of(JobStatus.COMPLETED, JobStatus.CANCELLED, JobStatus.EXPIRED);
        }
        return null;
    }

    public List<Job> getWorkerJobs(String mobileNumber) {
        return getWorkerJobs(mobileNumber, null, 0, 50);
    }

    public List<Job> getWorkerJobs(String mobileNumber, String type, int page, int size) {
        log.debug("Fetching worker jobs - mobile: {}, type: {}, page: {}, size: {}", mobileNumber, type, page, size);
        Criteria criteria = Criteria.where("workerMobileNumber").is(mobileNumber);
        List<JobStatus> statuses = resolveWorkerStatusFilter(type);
        if (statuses != null) {
            criteria = criteria.and("status").in(statuses);
        }

        Query q = Query.query(criteria)
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .with(org.springframework.data.domain.PageRequest.of(page, size));

        List<Job> jobs = mongoTemplate.find(q, Job.class);
        log.info("Found {} jobs for worker mobile: {}", jobs.size(), mobileNumber);
        return jobs;
    }

    private List<JobStatus> resolveWorkerStatusFilter(String type) {
        if ("active".equalsIgnoreCase(type)) {
            return List.of(JobStatus.PENDING_ACCEPTANCE, JobStatus.ASSIGNED);
        } else if ("completed".equalsIgnoreCase(type)) {
            return List.of(JobStatus.COMPLETED);
        }
        return null;
    }

    /**
     * Returns available jobs filtered by the worker's geo-location and skills.
     * Falls back to a wider geo-only search if the worker has no skills set.
     * Results are paginated (default 20 per page).
     */
    public List<Job> getMatchingJobs(String workerMobile) {
        return getMatchingJobs(workerMobile, 0, 20);
    }

    @Cacheable(value = "availableJobs", key = "#workerMobile + ':' + #page + ':' + #size",
               unless = "#result.isEmpty()")
    public List<Job> getMatchingJobs(String workerMobile, int page, int size) {
        log.debug("JobService: [ENTER] getMatchingJobs - worker: {}, page: {}, size: {}", workerMobile, page, size);

        var workerOpt = profileService.getWorkerProfile(workerMobile);
        if (workerOpt.isEmpty()) {
            log.warn("JobService: No worker profile found for {}, returning empty", workerMobile);
            return List.of();
        }

        var worker = workerOpt.get();
        double[] loc = worker.getLastLocation();

        List<JobStatus> matchStatuses = List.of(JobStatus.BROADCASTED, JobStatus.SCHEDULED);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);

        List<Job> jobs;
        if (loc == null || loc.length < 2) {
            log.warn("JobService: Worker {} has no stored location — returning all active jobs without geo filter", workerMobile);
            jobs = jobRepository.findByStatusIn(matchStatuses, pageable);
        } else {
            // Use the configured max radius (default 50km), converted to meters
            double radiusMeters = (worker.getTravelRadiusKm() > 0 ? worker.getTravelRadiusKm() : 50) * 1000.0;
            if (worker.getSkills() != null && !worker.getSkills().isEmpty()) {
                jobs = jobRepository.findMatchingJobs(
                        loc[0], loc[1], matchStatuses, worker.getSkills(), radiusMeters, pageable);
            } else {
                // Worker hasn't set skills — show all nearby jobs
                jobs = jobRepository.findNearbyJobs(
                        loc[0], loc[1], matchStatuses, radiusMeters, pageable);
            }
            log.info("JobService: Found {} matching jobs for worker {} within {}m", jobs.size(), workerMobile, radiusMeters);
        }

        log.info("JobService: Returning {} jobs for worker {}", jobs.size(), workerMobile);
        log.debug("JobService: [EXIT] getMatchingJobs");
        return jobs;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "availableJobs", allEntries = true),
        @CacheEvict(value = "seekerJobs",    allEntries = true)
    })
    public void acceptJob(String jobId, String workerMobile) {
        Job job = jobAcceptanceService.acceptJob(jobId, workerMobile);

        JobEvent event = JobEvent.builder()
                .jobId(job.getId())
                .eventType("JOB_ACCEPTED")
                .status(job.getStatus())
                .workerId(workerMobile)
                .build();
        saveOutboxEvent("job.status.updated", event);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "availableJobs", allEntries = true),
        @CacheEvict(value = "seekerJobs",    allEntries = true)
    })
    public Job completeJob(String jobId, String otp, String requestingUserMobile) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> WorklyException.notFound("Job not found"));

        if (job.getStatus() == JobStatus.COMPLETED) {
            throw WorklyException.badRequest("Job is already completed");
        }
        if (job.getStatus() != JobStatus.ASSIGNED) {
            throw WorklyException.badRequest("Job must be in ASSIGNED status to complete");
        }

        // Verify Worker
        if (job.getWorkerMobileNumber() == null || !job.getWorkerMobileNumber().equals(requestingUserMobile)) {
            throw WorklyException.forbidden("You are not the assigned worker for this job");
        }

        if (job.getCompletionOtp() == null || !job.getCompletionOtp().equals(otp)) {
            throw WorklyException.badRequest("Invalid OTP");
        }

        job.setStatus(JobStatus.COMPLETED);
        jobRepository.save(job);

        JobEvent event = JobEvent.builder()
                .jobId(job.getId())
                .eventType("JOB_COMPLETED")
                .status(job.getStatus())
                .workerId(requestingUserMobile)
                .build();
        saveOutboxEvent("job.status.updated", event);
        
        log.debug("JobService: [EXIT] completeJob - Successfully marked ID {} as completed and pushed notification event.", jobId);
        return job; // Added return statement
    }

    public Job getJobById(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> WorklyException.notFound("Job not found"));
    }
}
