package com.workly.modules.verification;

import com.workly.core.WorklyException;
import com.workly.modules.job.Job;
import com.workly.modules.job.JobRepository;
import com.workly.modules.job.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobVerificationService {

    private final JobRepository mongoJobRepository;
    private final JobCompletionRepository jpaJobRepository;

    @Transactional
    public JobCompletion verifyAndCompleteJob(String jobId, String otp, String requestingUserMobile) {
        log.debug("JobVerificationService: [ENTER] verifyAndCompleteJob - jobId: {}", jobId);
        Job mongoJob = mongoJobRepository.findById(jobId)
                .orElseThrow(() -> WorklyException.notFound("Job not found in MongoDB"));

        if (mongoJob.getWorkerMobileNumber() == null || !mongoJob.getWorkerMobileNumber().equals(requestingUserMobile)) {
            log.debug("JobVerificationService: [FAIL] {} is not the assigned worker for job {}", requestingUserMobile, jobId);
            throw WorklyException.forbidden("You are not the assigned worker for this job");
        }

        if (otp == null || mongoJob.getCompletionOtp() == null || !mongoJob.getCompletionOtp().equals(otp)) {
            log.debug("JobVerificationService: [FAIL] OTP mismatch for job {}", jobId);
            throw WorklyException.badRequest("Invalid completion OTP");
        }

        if (mongoJob.getStatus() == JobStatus.COMPLETED) {
            log.debug("JobVerificationService: [FAIL] Job {} is already completed", jobId);
            throw WorklyException.badRequest("Job is already completed");
        }

        log.debug("JobVerificationService: OTP verified. Creating PostgreSQL completion record");
        JobCompletion completion = jpaJobRepository.findByJobId(jobId)
                .orElse(new JobCompletion());

        completion.setJobId(jobId);
        completion.setWorkerMobile(mongoJob.getWorkerMobileNumber());
        completion.setSeekerMobile(mongoJob.getSeekerMobileNumber());
        completion.setVerificationOtp(otp);
        completion.setVerified(true);
        completion.setCompletedAt(LocalDateTime.now());

        JobCompletion savedCompletion = jpaJobRepository.save(completion);
        log.debug("JobVerificationService: PostgreSQL completion record saved. Updating MongoDB status");

        mongoJob.setStatus(JobStatus.COMPLETED);
        mongoJobRepository.save(mongoJob);

        log.debug("JobVerificationService: [EXIT] verifyAndCompleteJob - Dual-database commit complete for job {}", jobId);
        return savedCompletion;
    }
}
