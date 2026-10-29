package com.banking.notificationservice.client;

import com.banking.notificationservice.model.Contact;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Looks up contact details in Account Service — the single source of truth for email and phone. */
@Component
public class AccountDirectory {

    private final RestClient restClient;

    public AccountDirectory(RestClient.Builder builder, @Value("${account.service.url}") String accountServiceUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        this.restClient = builder.baseUrl(accountServiceUrl).requestFactory(requestFactory).build();
    }

    public Contact find(String accountNumber) {
        AccountView account = restClient.get()
                .uri("/api/v1/accounts/{accountNumber}", accountNumber)
                .retrieve()
                .body(AccountView.class);
        if (account == null) {
            throw new IllegalStateException("Account Service returned no account for " + accountNumber);
        }
        return new Contact(accountNumber, account.accountHolderName(), account.email(), account.phone());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountView(String accountHolderName, String email, String phone) {
    }
}
