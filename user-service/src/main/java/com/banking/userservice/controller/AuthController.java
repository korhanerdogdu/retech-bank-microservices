package com.banking.userservice.controller;

import com.banking.userservice.dto.AuthResponse;
import com.banking.userservice.dto.LoginRequest;
import com.banking.userservice.dto.RegisterRequest;
import com.banking.userservice.dto.UserResponse;
import com.banking.userservice.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    // Set by the API Gateway from the validated JWT; never trusted from clients
    public static final String USER_ID_HEADER = "X-User-Id";

    private final AuthService authService;

    // Sign up — creates the user and opens their account
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@RequestHeader(USER_ID_HEADER) String userId) {
        return ResponseEntity.ok(authService.me(userId));
    }
}
