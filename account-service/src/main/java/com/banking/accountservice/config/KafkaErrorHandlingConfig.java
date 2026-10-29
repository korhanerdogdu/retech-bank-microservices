package com.banking.accountservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failed records are retried 3 times (1s apart), then published to
 * "<topic>.DLT" so they can be inspected and replayed. Handlers are
 * idempotent, so a replay never moves money twice.
 */
@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("Giving up on {}-{}@{} after retries, sending to DLT: {}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage());
            recoverer.accept(record, exception);
        }, new FixedBackOff(1000L, 3L));
        return handler;
    }
}
