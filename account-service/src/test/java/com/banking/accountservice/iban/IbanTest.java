package com.banking.accountservice.iban;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IbanTest {

    @Test
    void acceptsKnownValidIbans() {
        // Published example IBANs
        assertThat(Iban.isValid("TR330006100519786457841326")).isTrue();
        assertThat(Iban.isValid("GB82WEST12345698765432")).isTrue();
        assertThat(Iban.isValid("DE89370400440532013000")).isTrue();
    }

    @Test
    void rejectsTyposWrongLengthAndGarbage() {
        assertThat(Iban.isValid("TR330006100519786457841327")).isFalse(); // one digit changed
        assertThat(Iban.isValid("TR33000610051978645784132")).isFalse();  // 25 characters
        assertThat(Iban.isValid("TR3300061005197864578413A6")).isFalse(); // letter in a Turkish IBAN
        assertThat(Iban.isValid("")).isFalse();
        assertThat(Iban.isValid(null)).isFalse();
        assertThat(Iban.isValid("hello world")).isFalse();
    }

    @Test
    void generatedIbansAreValidTurkishIbansThatEmbedTheAccountNumber() {
        String iban = Iban.forAccount("TR", "00999", "381196405717");

        assertThat(iban).hasSize(26).startsWith("TR").endsWith("0000381196405717");
        assertThat(Iban.bankCode(iban)).isEqualTo("00999");
        assertThat(Iban.isValid(iban)).isTrue();
    }

    @Test
    void everyGeneratedIbanPassesTheChecksum() {
        for (long n = 0; n < 2_000; n++) {
            String account = String.format("%012d", n * 499_999_999L % 1_000_000_000_000L);
            assertThat(Iban.isValid(Iban.forAccount("TR", "00999", account))).as(account).isTrue();
        }
    }

    @Test
    void normalizeAndFormat() {
        assertThat(Iban.normalize(" tr33 0006 1005 1978 6457 8413 26 ")).isEqualTo("TR330006100519786457841326");
        assertThat(Iban.format("TR330006100519786457841326")).isEqualTo("TR33 0006 1005 1978 6457 8413 26");
    }
}
