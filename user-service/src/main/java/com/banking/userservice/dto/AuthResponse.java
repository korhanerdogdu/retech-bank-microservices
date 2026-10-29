package com.banking.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    // Bearer token for the API Gateway
    private String accessToken;
    private long expiresInSeconds;
    private UserResponse user;
}
