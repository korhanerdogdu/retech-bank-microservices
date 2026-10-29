package com.banking.accountservice.iban;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IBAN helpers (ISO 13616, check digits per ISO 7064 MOD 97-10).
 *
 * Turkish IBANs are 26 characters: {@code TRkk BBBB BRAA AAAA AAAA AAAA AA}
 * — country, 2 check digits, 5-digit bank code, 1 reserve digit (0) and a
 * 16-digit account part. Retech Bank puts its 12-digit account number,
 * zero-padded to 16 digits, in the account part.
 */
public final class Iban {

    private static final Pattern SHAPE = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$");
    private static final Pattern TURKISH = Pattern.compile("^TR\\d{24}$");

    private Iban() {
    }

    /** Removes spaces and upper-cases, so "tr12 0099 ..." and "TR120099..." are the same IBAN. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** Structure and check digits are valid. Expects a normalized IBAN. */
    public static boolean isValid(String iban) {
        if (iban == null || !SHAPE.matcher(iban).matches()) {
            return false;
        }
        if (iban.startsWith("TR") && !TURKISH.matcher(iban).matches()) {
            return false;
        }
        return mod97(iban.substring(4) + iban.substring(0, 4)) == 1;
    }

    /** Builds the IBAN for one of our accounts. */
    public static String forAccount(String countryCode, String bankCode, String accountNumber) {
        String bban = bankCode + "0" + "0".repeat(16 - accountNumber.length()) + accountNumber;
        int check = 98 - mod97(bban + countryCode + "00");
        return countryCode + String.format("%02d", check) + bban;
    }

    /** Bank code of a normalized Turkish IBAN (characters 5–9). */
    public static String bankCode(String iban) {
        return iban.substring(4, 9);
    }

    /** "TR12 0099 9000 ..." — groups of four for display. */
    public static String format(String iban) {
        return iban.replaceAll("(.{4})(?!$)", "$1 ");
    }

    // Letters count as two digits (A=10 … Z=35); the remainder is computed piecewise
    static int mod97(String value) {
        int remainder = 0;
        for (char c : value.toCharArray()) {
            int digit = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = digit >= 10
                    ? (remainder * 100 + digit) % 97
                    : (remainder * 10 + digit) % 97;
        }
        return remainder;
    }
}
