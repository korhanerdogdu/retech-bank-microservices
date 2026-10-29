package com.banking.notificationservice.service;

import com.banking.notificationservice.channel.NotificationChannel;
import com.banking.notificationservice.client.AccountDirectory;
import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceTest {

    private static final Contact ALICE = new Contact("111111111111", "Alice", "alice@example.com", "+905551112233");
    private static final Contact BOB = new Contact("222222222222", "Bob", "bob@example.com", "5550000000");

    private final AccountDirectory directory = mock(AccountDirectory.class);
    private final RecordingChannel email = new RecordingChannel("email", false);
    private final RecordingChannel sms = new RecordingChannel("sms", true);
    private NotificationService service;

    @BeforeEach
    void setUp() {
        when(directory.find(ALICE.accountNumber())).thenReturn(ALICE);
        when(directory.find(BOB.accountNumber())).thenReturn(BOB);
        service = new NotificationService(directory, new NotificationTemplates(), List.of(email, sms));
    }

    @Test
    void otpGoesToTheAccountHoldersEmailWithoutAnyLinks() {
        service.consumeOtpGenerated(otpEvent());

        assertThat(email.sent).hasSize(1);
        Notification n = email.sent.get(0);
        assertThat(email.recipients).containsExactly("alice@example.com");
        assertThat(n.text()).contains("483920", "₺950,00", "Retech Bank app", "I didn't make this transfer");
        assertThat(n.html()).contains("483920").doesNotContain("href=");
        assertThat(n.text()).doesNotContain("http");
        assertThat(n.sensitive()).isTrue();
    }

    @Test
    void otpAlsoGoesBySmsWhenThePhoneCanReceiveIt() {
        service.consumeOtpGenerated(otpEvent());

        assertThat(sms.sent).hasSize(1);
        assertThat(sms.sent.get(0).sms()).contains("483920");
    }

    @Test
    void otpIsNeverLogged(CapturedOutput output) {
        service.consumeOtpGenerated(otpEvent());

        assertThat(output.getOut()).contains("[verification code]").doesNotContain("483920");
    }

    @Test
    void completedTransferAlertsBothSides() {
        service.consumeTransactionCompleted(Map.of(
                "transactionId", "tx-1",
                "senderAccountNumber", ALICE.accountNumber(),
                "receiverAccountNumber", BOB.accountNumber(),
                "amount", 100));

        assertThat(email.recipients).containsExactly("alice@example.com", "bob@example.com");
        assertThat(email.sent.get(0).subject()).isEqualTo("Debit alert: ₺100,00");
        assertThat(email.sent.get(1).subject()).isEqualTo("Credit alert: ₺100,00");
        assertThat(sms.sent).isEmpty(); // routine alerts are email-only
    }

    @Test
    void amountsAreTurkishLiraInTurkishFormat() {
        assertThat(NotificationTemplates.money(1234567.5)).isEqualTo("₺1.234.567,50");
        assertThat(NotificationTemplates.money("0.005")).isEqualTo("₺0,01");
        assertThat(NotificationTemplates.money(null)).isEqualTo("₺0,00");
    }

    @Test
    void htmlEscapesUntrustedText() {
        service.consumeFraudDetected(Map.of("accountNumber", ALICE.accountNumber(), "reason", "<script>x</script>"));

        assertThat(email.sent.get(0).html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void failureIsRethrownOnlyWhenNothingWasDelivered() {
        email.failing = true;
        service.consumeOtpGenerated(otpEvent()); // SMS still delivered -> no retry, no duplicate SMS
        assertThat(sms.sent).hasSize(1);

        sms.failing = true;
        assertThatThrownBy(() -> service.consumeOtpGenerated(otpEvent())).isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Object> otpEvent() {
        return Map.of(
                "transactionId", "tx-1",
                "accountNumber", ALICE.accountNumber(),
                "otp", "483920",
                "amount", 950.0,
                "reason", "Transaction exceeds 90% of account balance");
    }

    /** Test double that records what would have been sent. */
    static class RecordingChannel implements NotificationChannel {
        final String name;
        final boolean requiresSms;
        final List<Notification> sent = new ArrayList<>();
        final List<String> recipients = new ArrayList<>();
        boolean failing;

        RecordingChannel(String name, boolean requiresSms) {
            this.name = name;
            this.requiresSms = requiresSms;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean canDeliver(Contact contact, Notification notification) {
            return !requiresSms || (notification.sms() != null && contact.phone().startsWith("+"));
        }

        @Override
        public void send(Contact contact, Notification notification) {
            if (failing) {
                throw new IllegalStateException(name + " down");
            }
            sent.add(notification);
            recipients.add(requiresSms ? contact.phone() : contact.email());
        }

        @Override
        public String describeRecipient(Contact contact) {
            return "masked";
        }
    }
}
