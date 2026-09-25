package com.workly.modules.job.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    // Distributed lock: only one instance may run the relay at a time.
    // TTL is generous enough to cover a full relay batch but short enough to
    // recover quickly if the lock holder crashes without releasing.
    private static final String LOCK_KEY = "outbox:relay:lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final int SEND_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRIES = 5;

    @Scheduled(fixedDelay = 5000)
    public void relayEvents() {
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, lockValue, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return; // Another instance is already processing
        }

        try {
            List<OutboxEvent> pendingEvents = outboxEventRepository.findTop100ByProcessedFalseAndFailedFalseOrderByCreatedAtAsc();
            if (pendingEvents.isEmpty()) return;

            log.debug("Found {} pending outbox events to process", pendingEvents.size());

            for (OutboxEvent event : pendingEvents) {
                try {
                    kafkaTemplate.send(event.getTopic(), event.getPayload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    event.setProcessed(true);
                    outboxEventRepository.save(event);
                    log.debug("Successfully relayed outbox event {}", event.getId());
                } catch (Exception e) {
                    int retries = event.getRetryCount() + 1;
                    event.setRetryCount(retries);
                    if (retries >= MAX_RETRIES) {
                        event.setFailed(true);
                        log.error("Outbox event {} dead-lettered after {} retries. Topic: {}. Error: {}",
                                event.getId(), retries, event.getTopic(), e.getMessage());
                    } else {
                        log.warn("Failed to relay outbox event {} (attempt {}/{}). Will retry. Error: {}",
                                event.getId(), retries, MAX_RETRIES, e.getMessage());
                    }
                    outboxEventRepository.save(event);
                    break; // Maintain temporal order; next tick will retry from here
                }
            }
        } finally {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class), java.util.Collections.singletonList(LOCK_KEY), lockValue);
        }
    }
}
