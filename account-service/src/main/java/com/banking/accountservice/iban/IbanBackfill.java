package com.banking.accountservice.iban;

import com.banking.accountservice.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** On start-up, gives accounts opened before IBANs were introduced their IBAN. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IbanBackfill implements ApplicationRunner {

    private final AccountService accountService;

    @Override
    public void run(ApplicationArguments args) {
        int assigned = accountService.assignMissingIbans();
        if (assigned > 0) {
            log.info("Assigned IBANs to {} existing accounts", assigned);
        }
    }
}
