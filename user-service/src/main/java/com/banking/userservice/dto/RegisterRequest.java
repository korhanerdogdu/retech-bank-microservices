package com.banking.userservice.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;

    // Demo money to start the new account with
    @NotNull(message = "Opening deposit is required")
    @Positive(message = "Opening deposit must be positive")
    @DecimalMax(value = "1000000", message = "Opening deposit can be at most ₺1.000.000")
    private BigDecimal initialDeposit;
}
