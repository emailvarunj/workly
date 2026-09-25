package com.workly.modules.verification;

import com.workly.core.WorklyException;
import com.workly.modules.job.Job;
import com.workly.modules.job.JobRepository;
import com.workly.modules.job.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobVerificationServiceTest {

    @Mock
    private JobRepository mongoJobRepository;

    @Mock
    private JobCompletionRepository jpaJobRepository;

    private JobVerificationService verificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        verificationService = new JobVerificationService(mongoJobRepository, jpaJobRepository);
    }

    @Test
    void verifyAndCompleteJob_ShouldSucceedWithValidOtp() {
        String jobId = "job123";
        String otp = "1234";
        Job mongoJob = new Job();
        mongoJob.setCompletionOtp(otp);
        mongoJob.setStatus(JobStatus.ASSIGNED);
        mongoJob.setWorkerMobileNumber("w1");

        when(mongoJobRepository.findById(jobId)).thenReturn(Optional.of(mongoJob));
        when(jpaJobRepository.findByJobId(jobId)).thenReturn(Optional.empty());
        when(jpaJobRepository.save(any(JobCompletion.class))).thenReturn(new JobCompletion());

        verificationService.verifyAndCompleteJob(jobId, otp, "w1");

        assertEquals(JobStatus.COMPLETED, mongoJob.getStatus());
        verify(mongoJobRepository).save(mongoJob);
        verify(jpaJobRepository).save(any(JobCompletion.class));
    }

    @Test
    void verifyAndCompleteJob_ShouldThrowExceptionForInvalidOtp() {
        String jobId = "job123";
        Job mongoJob = new Job();
        mongoJob.setCompletionOtp("1111");
        mongoJob.setWorkerMobileNumber("w1");

        when(mongoJobRepository.findById(jobId)).thenReturn(Optional.of(mongoJob));

        assertThrows(WorklyException.class, () -> verificationService.verifyAndCompleteJob(jobId, "2222", "w1"));
    }

    @Test
    void verifyAndCompleteJob_ShouldThrowExceptionForNonAssignedWorker() {
        String jobId = "job123";
        Job mongoJob = new Job();
        mongoJob.setCompletionOtp("1234");
        mongoJob.setWorkerMobileNumber("w1");

        when(mongoJobRepository.findById(jobId)).thenReturn(Optional.of(mongoJob));

        assertThrows(WorklyException.class, () -> verificationService.verifyAndCompleteJob(jobId, "1234", "someone-else"));
    }
}
