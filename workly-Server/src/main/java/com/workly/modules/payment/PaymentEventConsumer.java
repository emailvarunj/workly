package com.workly.modules.payment;

import com.workly.modules.job.JobEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentService paymentService;

    @KafkaListener(topics = "job.status.updated", groupId = "payment-group")
    public void handleJobEvents(JobEvent event) {
        log.debug("PaymentEventConsumer: [ENTER] - Consumed Job event: {}", event.getEventType());
        if ("JOB_COMPLETED".equals(event.getEventType())) {
            log.info("PaymentEventConsumer: Processing JOB_COMPLETED for jobId {}", event.getJobId());
            paymentService.completePayment(event.getJobId());
        }
        log.debug("PaymentEventConsumer: [EXIT] event processed");
    }
}
