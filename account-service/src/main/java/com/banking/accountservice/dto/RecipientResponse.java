package com.banking.accountservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a sender may see about someone else's account before confirming a
 * transfer: the IBAN and an abbreviated name ("Bob K."). The account number
 * is part of a Turkish IBAN anyway, so it is returned for internal routing.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecipientResponse {
    private String iban;
    private String accountNumber;
    private String displayName;
    private boolean canReceive;
}
