package com.workly.modules.verification;

import com.workly.core.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/verification")
@RequiredArgsConstructor
@Slf4j
public class VerificationController {

    private final JobVerificationService verificationService;

    @PostMapping("/complete")
    public ApiResponse<JobCompletion> completeJob(@RequestBody VerificationRequest request) {
        log.debug("VerificationController: [ENTER] completeJob - jobId: {}", request.getJobId());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String mobileNumber = auth.getName();
        JobCompletion result = verificationService.verifyAndCompleteJob(request.getJobId(), request.getOtp(), mobileNumber);
        log.debug("VerificationController: [EXIT] completeJob - jobId: {} verified and completed", request.getJobId());
        return ApiResponse.success(result, "Job verified and completed");
    }

    @Data
    public static class VerificationRequest {
        private String jobId;
        private String otp;
    }
}
