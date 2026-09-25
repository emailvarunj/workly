package com.workly.modules.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(name = "sms.provider", havingValue = "mock", matchIfMissing = true)
public class MockOtpProvider implements OtpProvider {
    @Override
    public void sendOtp(String mobileNumber, String otp) {
        log.info("Sending MOCK OTP to mobile number [{}]", mobileNumber);
    }
}
