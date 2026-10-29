package com.banking.notificationservice.service;

import com.banking.notificationservice.channel.NotificationChannel;
import com.banking.notificationservice.client.AccountDirectory;
import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns SAGA events into customer notifications. Contact details come from
 * Account Service; each notification goes to every channel that can reach
 * the customer (email always, SMS when Twilio is configured).
 *
 * A failure is rethrown so Kafka retries it — but only when no channel
 * delivered, to avoid sending the same message twice on one channel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final AccountDirectory accountDirectory;
    private final NotificationTemplates templates;
    private final List<NotificationChannel> channels;

    /**
     * Transaction completed — debit sender, credit receiver.
     */
    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionCompleted(
            @Payload Map<String, Object> payload) {
        String senderAccount = (String) payload.get("senderAccountNumber");
        String receiverAccount = (String) payload.get("receiverAccountNumber");
        Object amount = payload.get("amount");

        notify(senderAccount, contact -> templates.debit(contact, amount, receiverAccount));
        notify(receiverAccount, contact -> templates.credit(contact, amount, senderAccount));
    }


    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(
            @Payload Map<String, Object> payload) {
        String accountNumber = (String) payload.get("accountNumber");
        String reason = (String) payload.get("reason");

        notify(accountNumber, contact -> templates.accountBlocked(contact, reason));
    }


    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeOtpGenerated(
            @Payload Map<String, Object> payload) {
        String accountNumber = (String) payload.get("accountNumber");
        String otp = (String) payload.get("otp");
        String transactionId = (String) payload.get("transactionId");
        String reason = (String) payload.get("reason");

        notify(accountNumber, contact ->
                templates.verificationCode(contact, transactionId, otp, payload.get("amount"), reason));
    }


    @KafkaListener(topics = "transaction.refunded")
    public void consumeTransactionRefunded(
            @Payload Map<String, Object> payload) {
        String senderAccount = (String) payload.get("senderAccountNumber");
        String reason = (String) payload.get("reason");

        notify(senderAccount, contact -> templates.refund(contact, payload.get("amount"), reason));
    }

    private void notify(String accountNumber, Function<Contact, Notification> render) {
        Contact contact = accountDirectory.find(accountNumber);
        Notification notification = render.apply(contact);

        List<String> delivered = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (NotificationChannel channel : channels) {
            if (!channel.canDeliver(contact, notification)) {
                continue;
            }
            try {
                channel.send(contact, notification);
                delivered.add(channel.name() + " " + channel.describeRecipient(contact));
            } catch (RuntimeException e) {
                log.error("{} notification to account {} failed: {}", channel.name(), accountNumber, e.getMessage());
                failures.add(e);
            }
        }

        if (delivered.isEmpty()) {
            if (!failures.isEmpty()) {
                throw failures.get(0);
            }
            log.warn("No channel can reach account {} - notification '{}' dropped", accountNumber,
                    notification.subject());
            return;
        }

        // Never log the body of a sensitive notification — it contains the OTP
        log.info("Notification sent - account: {} subject: {} via: {}{}", accountNumber,
                notification.sensitive() ? "[verification code]" : notification.subject(),
                delivered, notification.sensitive() ? "" : " message: " + notification.text());
    }
}
