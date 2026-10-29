package com.banking.notificationservice.model;

/**
 * One message, rendered for every channel.
 *
 * @param sms text for SMS, or null when this notification is email-only
 * @param sensitive true when the content holds a secret (OTP) and must never be logged
 */
public record Notification(String subject, String text, String html, String sms, boolean sensitive) {
}
