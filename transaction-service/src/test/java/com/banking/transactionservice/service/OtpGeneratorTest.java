package com.banking.transactionservice.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OtpGeneratorTest {

    private final OtpGenerator generator = new OtpGenerator();

    @Test
    void generatesSixDigitCodesInRange() {
        for (int i = 0; i < 10_000; i++) {
            String otp = generator.generate();
            assertThat(otp).matches("\\d{6}");
            assertThat(Integer.parseInt(otp)).isBetween(100_000, 999_999);
        }
    }

    @Test
    void codesAreNotRepeatedPredictably() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.generate());
        }
        // 1000 draws from 900k values: collisions are possible but must be rare
        assertThat(seen.size()).isGreaterThan(990);
    }
}
