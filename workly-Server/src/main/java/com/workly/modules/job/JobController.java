package com.workly.modules.job;

import com.workly.core.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Slf4j
public class JobController {

    private final JobService jobService;
    private final com.workly.modules.profile.ProfileService profileService;
    private final com.workly.modules.promotion.PromotionService promotionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<com.workly.modules.job.dto.JobDTO> createJob(
            @RequestBody com.workly.modules.job.dto.JobDTO jobDto) {
        log.debug("JobController: [ENTER] createJob - DTO params: {}", jobDto);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        log.info("Received request to create job for mobile: {}", mobileNumber);

        Job job = new Job();
        job.setSeekerMobileNumber(mobileNumber);
        job.setTitle(jobDto.getTitle());
        job.setDescription(jobDto.getDescription());
        job.setRequiredSkills(jobDto.getRequiredSkills());
        if (jobDto.getRequiredSkill() != null
                && (job.getRequiredSkills() == null || job.getRequiredSkills().isEmpty())) {
            job.setRequiredSkills(List.of(jobDto.getRequiredSkill()));
        }
        job.setBudget(jobDto.getBudget() != null ? jobDto.getBudget() : 0.0);
        job.setStatus(jobDto.getStatus());
        job.setJobType(jobDto.getJobType());
        // Infer immediate from jobType when the boolean field is not explicitly set
        job.setImmediate(jobDto.isImmediate() || jobDto.getJobType() == JobType.IMMEDIATE);
        job.setAssignmentMode(jobDto.getAssignmentMode());
        job.setWorkerMobileNumber(jobDto.getWorkerMobileNumber());
        if (jobDto.getPreferredDateTime() > 0) {
            job.setScheduledTime(java.time.Instant.ofEpochMilli(jobDto.getPreferredDateTime())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        }

        if (jobDto.getLocation() != null) {
            job.setAddress(jobDto.getLocation().getAddress());
            job.setLocation(new double[] { jobDto.getLocation().getLongitude(), jobDto.getLocation().getLatitude() });
        }
        job.setSearchRadiusKm(jobDto.getSearchRadiusKm());

        if (jobDto.getAppliedPromoCode() != null && !jobDto.getAppliedPromoCode().isEmpty() && job.getBudget() > 0) {
            com.workly.modules.promotion.PromotionValidationResult promoResult = promotionService.validatePromotion(jobDto.getAppliedPromoCode(), job.getBudget());
            job.setAppliedPromoCode(promoResult.getCode());
            job.setDiscountAmount(promoResult.getDiscountAmount());
            log.info("JobController: Applied promo code {} saving {} to budget", promoResult.getCode(), promoResult.getDiscountAmount());
        }

        Job createdJob = jobService.createJob(job);
        log.info("Job created successfully with ID: {} for mobile: {}", createdJob.getId(), mobileNumber);
        log.debug("JobController: [EXIT] createJob - Mapping job network entity {} back to transfer DTO.", createdJob.getId());
        return ApiResponse.success(toDto(createdJob), "Job created successfully");
    }

    @GetMapping
    public ApiResponse<List<com.workly.modules.job.dto.JobDTO>> getJobs(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        log.debug("JobController: [ENTER] getJobs - type: {}, page: {}, size: {}", type, page, size);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        List<Job> jobs = jobService.getSeekerJobs(mobileNumber, type, page, Math.min(size, 50));
        log.debug("JobController: [EXIT] getJobs - Returning {} items", jobs.size());
        return ApiResponse.success(jobs.stream().map(this::toDto).toList(), "Jobs retrieved");
    }

    @GetMapping("/seeker")
    public ApiResponse<List<com.workly.modules.job.dto.JobDTO>> getSeekerJobs(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        return ApiResponse.success(jobService.getSeekerJobs(mobileNumber, type, page, Math.min(size, 50)).stream().map(this::toDto).toList(),
                "Jobs retrieved");
    }

    @GetMapping("/worker")
    public ApiResponse<List<com.workly.modules.job.dto.JobDTO>> getWorkerJobs(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        return ApiResponse.success(jobService.getWorkerJobs(mobileNumber, type, page, Math.min(size, 50)).stream().map(this::toDto).toList(),
                "Jobs retrieved");
    }

    @GetMapping("/available")
    public ApiResponse<List<com.workly.modules.job.dto.JobDTO>> getAvailableJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        return ApiResponse.success(jobService.getMatchingJobs(mobileNumber, page, Math.min(size, 20)).stream().map(this::toDto).toList(),
                "Available jobs retrieved");
    }

    @PostMapping("/{jobId}/accept")
    public ApiResponse<Void> acceptJob(@PathVariable String jobId) {
        log.debug("JobController: [ENTER] acceptJob - Path param ID: {}", jobId);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        jobService.acceptJob(jobId, mobileNumber);
        log.debug("JobController: [EXIT] acceptJob - Process completed, delegating successful void response null payload.");
        return ApiResponse.success(null, "Job accepted successfully");
    }

    @PostMapping("/{jobId}/complete")
    public ApiResponse<Void> completeJob(@PathVariable String jobId,
            @RequestBody java.util.Map<String, String> payload) {
        log.debug("JobController: [ENTER] completeJob - ID {} requested resolution via MAP payload length: {}", jobId, payload.size());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        String otp = payload.get("otp");
        if (otp == null || otp.isBlank()) {
            log.debug("JobController: [FAIL] completeJob - Missing OTP enforcement check tripped.");
            throw com.workly.core.WorklyException.badRequest("OTP is required");
        }
        jobService.completeJob(jobId, otp, mobileNumber);
        log.debug("JobController: [EXIT] completeJob - Handled OTP match internally, finalizing response.");
        return ApiResponse.success(null, "Job completed successfully");
    }

    @PatchMapping("/{jobId}/status")
    public ApiResponse<com.workly.modules.job.dto.JobDTO> updateStatus(@PathVariable String jobId,
            @RequestParam JobStatus status) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        return ApiResponse.success(toDto(jobService.updateJobStatus(jobId, status, mobileNumber)), "Status updated");
    }

    @PutMapping("/{jobId}")
    public ApiResponse<com.workly.modules.job.dto.JobDTO> updateJob(@PathVariable String jobId,
            @RequestBody com.workly.modules.job.dto.JobDTO jobDto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();

        Job job = new Job();
        if (jobDto.getPreferredDateTime() > 0) {
            job.setScheduledTime(java.time.Instant.ofEpochMilli(jobDto.getPreferredDateTime())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        }
        return ApiResponse.success(toDto(jobService.updateJob(jobId, job, mobileNumber)), "Job updated successfully");
    }

    @GetMapping("/{jobId}/tracking")
    public ApiResponse<com.workly.modules.job.dto.LocationDTO> getTrackingLocation(@PathVariable String jobId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        
        Job job = jobService.getJobById(jobId);
        if (!job.getSeekerMobileNumber().equals(mobileNumber)) {
            throw com.workly.core.WorklyException.forbidden("You do not own this job");
        }
        if (job.getStatus() != JobStatus.ASSIGNED) {
            throw com.workly.core.WorklyException.badRequest("Tracking is only available when a worker is assigned");
        }

        com.workly.modules.profile.WorkerProfile worker = profileService.getWorkerProfile(job.getWorkerMobileNumber())
                .orElseThrow(() -> com.workly.core.WorklyException.notFound("Worker profile not found"));
        
        com.workly.modules.job.dto.LocationDTO locationDto = new com.workly.modules.job.dto.LocationDTO();
        if (worker.getLastLocation() != null && worker.getLastLocation().length >= 2) {
            locationDto.setLongitude(worker.getLastLocation()[0]);
            locationDto.setLatitude(worker.getLastLocation()[1]);
        }
        
        return ApiResponse.success(locationDto, "Tracking fetched");
    }

    private com.workly.modules.job.dto.JobDTO toDto(Job job) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return toDto(job, auth != null ? auth.getName() : null);
    }

    private com.workly.modules.job.dto.JobDTO toDto(Job job, String requesterMobileNumber) {
        com.workly.modules.job.dto.JobDTO dto = new com.workly.modules.job.dto.JobDTO();
        dto.setId(job.getId());
        dto.setTitle(job.getTitle());
        dto.setDescription(job.getDescription());
        dto.setRequiredSkills(job.getRequiredSkills());
        if (job.getRequiredSkills() != null && !job.getRequiredSkills().isEmpty()) {
            dto.setRequiredSkill(job.getRequiredSkills().get(0));
        }
        dto.setBudget(job.getBudget());
        dto.setStatus(job.getStatus());
        dto.setImmediate(job.isImmediate());
        dto.setJobType(job.getJobType());
        dto.setAssignmentMode(job.getAssignmentMode());
        dto.setSearchRadiusKm(job.getSearchRadiusKm());
        if (job.getScheduledTime() != null) {
            dto.setPreferredDateTime(
                    job.getScheduledTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        dto.setPenaltyAmount(job.getPenaltyAmount());
        dto.setCancellationReason(job.getCancellationReason());
        dto.setAppliedPromoCode(job.getAppliedPromoCode());
        dto.setDiscountAmount(job.getDiscountAmount());

        // Identity fields
        dto.setSeekerMobileNumber(job.getSeekerMobileNumber());
        // Completion OTP is handed to the seeker in person to give to the worker — never expose
        // it to the worker via the API, or the OTP verification step is pointless.
        if (job.getSeekerMobileNumber() != null && job.getSeekerMobileNumber().equals(requesterMobileNumber)) {
            dto.setCompletionOtp(job.getCompletionOtp());
        }
        dto.setWorkerId(job.getWorkerMobileNumber());

        // Seeker name — fetch from seeker profile
        if (job.getSeekerMobileNumber() != null) {
            profileService.getSeekerProfile(job.getSeekerMobileNumber())
                    .ifPresent(sp -> dto.setSeekerName(sp.getName()));
        }

        // Worker details — only populated after assignment
        if (job.getWorkerMobileNumber() != null) {
            dto.setWorkerMobileNumber(job.getWorkerMobileNumber());
            profileService.getWorkerProfile(job.getWorkerMobileNumber()).ifPresent(wp ->
                    dto.setWorkerName(wp.getName()));
        }

        com.workly.modules.job.dto.LocationDTO locationDto = new com.workly.modules.job.dto.LocationDTO();
        locationDto.setAddress(job.getAddress());
        if (job.getLocation() != null && job.getLocation().length >= 2) {
            locationDto.setLongitude(job.getLocation()[0]);
            locationDto.setLatitude(job.getLocation()[1]);
        }
        dto.setLocation(locationDto);

        return dto;
    }
}
