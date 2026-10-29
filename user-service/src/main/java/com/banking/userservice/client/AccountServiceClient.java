package com.banking.userservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

@FeignClient(name = "account-service", url = "${account.service.url}")
public interface AccountServiceClient {

    @PostMapping("/api/v1/accounts")
    OpenedAccount createAccount(@RequestBody NewAccount request);

    record NewAccount(String accountHolderName, String email, String phone, String accountType,
                      BigDecimal initialDeposit, String ownerUserId) {
    }

    record OpenedAccount(String accountNumber) {
    }
}
