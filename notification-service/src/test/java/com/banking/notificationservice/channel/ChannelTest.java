package com.banking.notificationservice.channel;

import com.banking.notificationservice.config.NotificationProperties;
import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChannelTest {

    private static final Contact CONTACT = new Contact("111111111111", "Alice", "alice@example.com", "+90 555 111 2233");
    private static final Notification OTP = new Notification("Your code", "code 123456", "<p>123456</p>", "SMS 123456", true);

    @Test
    void emailIsMultipartFromTheConfiguredSender() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        NotificationProperties properties = new NotificationProperties();
        properties.getEmail().setFrom("Retech Bank <no-reply@retechbank.local>");

        EmailChannel channel = new EmailChannel(sender, properties);
        channel.send(CONTACT, OTP);

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        message.getValue().writeTo(raw);
        assertThat(message.getValue().getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(message.getValue().getFrom()[0].toString()).contains("no-reply@retechbank.local");
        assertThat(raw.toString()).contains("text/plain", "text/html");
        assertThat(channel.describeRecipient(CONTACT)).isEqualTo("a***@example.com");
    }

    @Test
    void smsIsDisabledUntilTwilioIsConfigured() {
        TwilioSmsChannel channel = new TwilioSmsChannel(new NotificationProperties(), RestClient.builder());
        assertThat(channel.canDeliver(CONTACT, OTP)).isFalse();
    }

    @Test
    void smsSkipsPhonesThatAreNotInternationalFormat() {
        TwilioSmsChannel channel = new TwilioSmsChannel(twilioProperties(), RestClient.builder());
        assertThat(channel.canDeliver(new Contact("1", "A", "a@b.c", "05551112233"), OTP)).isFalse();
        assertThat(channel.canDeliver(CONTACT, OTP)).isTrue();
        assertThat(channel.canDeliver(CONTACT, new Notification("s", "t", "h", null, false))).isFalse();
    }

    @Test
    void smsPostsToTwilioWithBasicAuth() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic QUMxMjM6c2VjcmV0")) // AC123:secret
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "To", "+905551112233", "From", "+15005550006", "Body", "SMS 123456")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new TwilioSmsChannel(twilioProperties(), builder).send(CONTACT, OTP);

        twilio.verify();
    }

    private static NotificationProperties twilioProperties() {
        NotificationProperties properties = new NotificationProperties();
        properties.getSms().getTwilio().setAccountSid("AC123");
        properties.getSms().getTwilio().setAuthToken("secret");
        properties.getSms().getTwilio().setFromNumber("+15005550006");
        return properties;
    }
}
