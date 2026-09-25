package com.workly.modules.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workly.common.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VerificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class VerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JobVerificationService verificationService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Test
    @WithMockUser
    void verify_ShouldReturnSuccess() throws Exception {
        VerificationController.VerificationRequest request = new VerificationController.VerificationRequest();
        request.setJobId("job123");
        request.setOtp("1234");

        when(verificationService.verifyAndCompleteJob(anyString(), anyString(), anyString())).thenReturn(new JobCompletion());

        mockMvc.perform(post("/api/v1/verification/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
