package com.banking.notificationservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private Email email = new Email();
    private Sms sms = new Sms();

    @Data
    public static class Email {
        private boolean enabled = true;
        private String from = "Retech Bank <no-reply@retechbank.local>";
    }

    @Data
    public static class Sms {
        private Twilio twilio = new Twilio();
    }

    @Data
    public static class Twilio {
        private String accountSid;
        private String authToken;
        private String fromNumber;

        public boolean isConfigured() {
            return hasText(accountSid) && hasText(authToken) && hasText(fromNumber);
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }
}
