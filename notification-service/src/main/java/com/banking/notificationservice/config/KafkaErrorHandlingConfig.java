package com.banking.notificationservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * A notification that could not be delivered (mail server or Account Service
 * down) is retried 3 times, 2s apart, then logged and skipped so one bad
 * message never blocks the topic.
 */
@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler((record, exception) ->
                log.error("Giving up on notification {}-{}@{}: {}",
                        record.topic(), record.partition(), record.offset(), exception.getMessage()),
                new FixedBackOff(2000L, 3L));
    }
}
