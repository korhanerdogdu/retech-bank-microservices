package com.banking.notificationservice.channel;

import com.banking.notificationservice.config.NotificationProperties;
import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.regex.Pattern;

/**
 * SMS through Twilio's REST API. Disabled unless notification.sms.twilio.* is set.
 * Only phones in international E.164 format (e.g. +905551234567) are messaged.
 */
@Component
public class TwilioSmsChannel implements NotificationChannel {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final NotificationProperties.Twilio twilio;
    private final RestClient restClient;

    public TwilioSmsChannel(NotificationProperties properties, RestClient.Builder builder) {
        this.twilio = properties.getSms().getTwilio();
        this.restClient = builder.baseUrl("https://api.twilio.com/2010-04-01").build();
    }

    @Override
    public String name() {
        return "sms";
    }

    @Override
    public boolean canDeliver(Contact contact, Notification notification) {
        return twilio.isConfigured()
                && notification.sms() != null
                && contact.phone() != null
                && E164.matcher(contact.phone().replace(" ", "")).matches();
    }

    @Override
    public void send(Contact contact, Notification notification) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", contact.phone().replace(" ", ""));
        form.add("From", twilio.getFromNumber());
        form.add("Body", notification.sms());

        restClient.post()
                .uri("/Accounts/{sid}/Messages.json", twilio.getAccountSid())
                .headers(h -> h.setBasicAuth(twilio.getAccountSid(), twilio.getAuthToken()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public String describeRecipient(Contact contact) {
        String phone = contact.phone().replace(" ", "");
        return "***" + phone.substring(Math.max(0, phone.length() - 3));
    }
}
