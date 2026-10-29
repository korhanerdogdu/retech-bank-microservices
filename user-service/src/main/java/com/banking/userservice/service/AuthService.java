package com.banking.userservice.service;

import com.banking.userservice.client.AccountServiceClient;
import com.banking.userservice.dto.AuthResponse;
import com.banking.userservice.dto.LoginRequest;
import com.banking.userservice.dto.RegisterRequest;
import com.banking.userservice.dto.UserResponse;
import com.banking.userservice.exception.AuthException;
import com.banking.userservice.model.User;
import com.banking.userservice.repository.UserRepository;
import com.banking.userservice.security.JwtIssuer;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    // Same message for unknown email and wrong password — don't reveal which accounts exist
    static final String BAD_CREDENTIALS = "Email or password is incorrect";

    private final UserRepository userRepository;
    private final AccountServiceClient accountServiceClient;
    private final JwtIssuer jwtIssuer;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // Compared against when the email is unknown, so both paths take the same time
    private final String dummyHash = passwordEncoder.encode("timing-equaliser");

    /**
     * Creates the user and opens their bank account. If the account cannot be
     * opened the user is removed again, so no user exists without an account.
     */
    public AuthResponse register(RegisterRequest request) {
        String email = normalise(request.getEmail());
        log.info("Registering user: {}", email);

        if (userRepository.existsByEmail(email)) {
            throw new AuthException(HttpStatus.CONFLICT, "An account already exists for " + email);
        }

        User user = new User();
        user.setFullName(request.getFullName().trim());
        user.setEmail(email);
        user.setPhone(request.getPhone().trim());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Concurrent sign-up with the same email lost the unique-constraint race
            throw new AuthException(HttpStatus.CONFLICT, "An account already exists for " + email);
        }

        try {
            AccountServiceClient.OpenedAccount account = accountServiceClient.createAccount(
                    new AccountServiceClient.NewAccount(user.getFullName(), email, user.getPhone(),
                            "SAVINGS", request.getInitialDeposit(), user.getId()));
            user.setAccountNumber(account.accountNumber());
            user = userRepository.save(user);
        } catch (FeignException e) {
            log.error("Opening the account for {} failed - removing the user: {}", email, e.getMessage());
            userRepository.delete(user);
            if (e.status() == HttpStatus.CONFLICT.value()) {
                throw new AuthException(HttpStatus.CONFLICT, "An account already exists for " + email);
            }
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Your account could not be opened right now. Please try again.");
        }

        log.info("User {} registered with account {}", user.getId(), user.getAccountNumber());
        return authResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalise(request.getEmail())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;

        if (!passwordEncoder.matches(request.getPassword(), hash) || user == null) {
            log.warn("Failed login for {}", normalise(request.getEmail()));
            throw new AuthException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        return authResponse(user);
    }

    public UserResponse me(String userId) {
        return userRepository.findById(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "Log in to continue"));
    }

    private AuthResponse authResponse(User user) {
        return new AuthResponse(jwtIssuer.issue(user), jwtIssuer.ttlSeconds(), toResponse(user));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getAccountNumber());
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
