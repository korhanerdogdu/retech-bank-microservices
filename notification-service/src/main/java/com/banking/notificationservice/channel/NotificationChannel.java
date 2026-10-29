package com.banking.notificationservice.channel;

import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;

/** A way to reach a customer (email, SMS, ...). */
public interface NotificationChannel {

    String name();

    /** True when this channel is configured and can reach the contact with this notification. */
    boolean canDeliver(Contact contact, Notification notification);

    /** Sends the notification; throws on failure. */
    void send(Contact contact, Notification notification);

    /** Destination for logs, with personal data masked. */
    String describeRecipient(Contact contact);
}
