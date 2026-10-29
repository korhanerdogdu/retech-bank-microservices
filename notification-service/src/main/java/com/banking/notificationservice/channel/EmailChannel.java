package com.banking.notificationservice.channel;

import com.banking.notificationservice.config.NotificationProperties;
import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * SMTP email. Locally this is Mailpit; set spring.mail.* to Resend's SMTP
 * relay (smtp.resend.com) for real delivery — no code change needed.
 */
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean canDeliver(Contact contact, Notification notification) {
        return properties.getEmail().isEnabled() && contact.email() != null && contact.email().contains("@");
    }

    @Override
    public void send(Contact contact, Notification notification) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getEmail().getFrom());
            helper.setTo(contact.email());
            helper.setSubject(notification.subject());
            helper.setText(notification.text(), notification.html());
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build email: " + e.getMessage(), e);
        }
    }

    @Override
    public String describeRecipient(Contact contact) {
        String email = contact.email();
        int at = email.indexOf('@');
        return (at <= 1 ? "*" : email.charAt(0) + "***") + email.substring(at);
    }
}
