package com.banking.userservice.service;

import com.banking.userservice.client.AccountServiceClient;
import com.banking.userservice.dto.AuthResponse;
import com.banking.userservice.dto.LoginRequest;
import com.banking.userservice.dto.RegisterRequest;
import com.banking.userservice.exception.AuthException;
import com.banking.userservice.model.User;
import com.banking.userservice.repository.UserRepository;
import com.banking.userservice.security.JwtIssuer;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private static final String SECRET = "test-secret-that-is-definitely-32-bytes-long";

    private final UserRepository repository = mock(UserRepository.class);
    private final AccountServiceClient accountClient = mock(AccountServiceClient.class);
    private final Map<String, User> users = new HashMap<>();
    private AuthService service;

    @BeforeEach
    void setUp() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(i -> store(i.getArgument(0)));
        when(repository.save(any(User.class))).thenAnswer(i -> store(i.getArgument(0)));
        when(repository.existsByEmail(anyString())).thenAnswer(i ->
                users.values().stream().anyMatch(u -> u.getEmail().equals(i.getArgument(0))));
        when(repository.findByEmail(anyString())).thenAnswer(i ->
                users.values().stream().filter(u -> u.getEmail().equals(i.getArgument(0))).findFirst());
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(users.get(i.<String>getArgument(0))));
        doAnswer(i -> users.remove(i.<User>getArgument(0).getId())).when(repository).delete(any(User.class));
        when(accountClient.createAccount(any())).thenReturn(new AccountServiceClient.OpenedAccount("123456789012"));

        service = new AuthService(repository, accountClient, new JwtIssuer(SECRET, "retech-bank", 60));
    }

    @Test
    void registerOpensAnAccountAndReturnsAToken() throws Exception {
        AuthResponse response = service.register(register("Alice@Example.com"));

        assertThat(response.getUser().getAccountNumber()).isEqualTo("123456789012");
        assertThat(response.getUser().getEmail()).isEqualTo("alice@example.com");
        verify(accountClient).createAccount(argThat(a ->
                a.ownerUserId().equals(response.getUser().getId()) && a.initialDeposit().compareTo(new BigDecimal("5000")) == 0));

        SignedJWT jwt = SignedJWT.parse(response.getAccessToken());
        assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(response.getUser().getId());
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("retech-bank");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isInTheFuture();
    }

    @Test
    void passwordsAreStoredHashed() {
        service.register(register("alice@example.com"));

        User stored = users.values().iterator().next();
        assertThat(stored.getPasswordHash()).doesNotContain("correct horse").startsWith("$2a$");
    }

    @Test
    void duplicateEmailIsRejected() {
        service.register(register("alice@example.com"));

        assertThatThrownBy(() -> service.register(register("ALICE@example.com")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void failedAccountOpeningRemovesTheUser() {
        when(accountClient.createAccount(any())).thenThrow(feignError(503));

        assertThatThrownBy(() -> service.register(register("alice@example.com")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(users).isEmpty();
    }

    @Test
    void loginWithCorrectPasswordReturnsAToken() {
        service.register(register("alice@example.com"));

        AuthResponse response = service.login(new LoginRequest(" Alice@example.com ", "correct horse"));

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getUser().getAccountNumber()).isEqualTo("123456789012");
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() {
        service.register(register("alice@example.com"));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice@example.com", "wrong password")))
                .hasMessage(AuthService.BAD_CREDENTIALS);
        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "correct horse")))
                .hasMessage(AuthService.BAD_CREDENTIALS);
    }

    @Test
    void shortSecretsAreRefused() {
        assertThatThrownBy(() -> new JwtIssuer("too-short", "retech-bank", 60))
                .isInstanceOf(IllegalStateException.class);
    }

    private User store(User user) {
        if (user.getId() == null) user.setId("user-" + (users.size() + 1));
        users.put(user.getId(), user);
        return user;
    }

    private static RegisterRequest register(String email) {
        return new RegisterRequest("Alice Smith", email, "+905551112233", "correct horse", new BigDecimal("5000"));
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/accounts", Map.of(),
                null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("createAccount", Response.builder()
                .status(status).reason("error").request(request).headers(Map.of())
                .body("{}", StandardCharsets.UTF_8).build());
    }
}
