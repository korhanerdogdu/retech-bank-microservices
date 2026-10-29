package com.banking.userservice.security;

import com.banking.userservice.model.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues HS256 access tokens. The API Gateway validates them with the same
 * secret and forwards the user id (the "sub" claim) as X-User-Id.
 */
@Component
public class JwtIssuer {

    private final MACSigner signer;
    private final String issuer;
    private final Duration ttl;

    public JwtIssuer(@Value("${security.jwt.secret}") String secret,
                     @Value("${security.jwt.issuer}") String issuer,
                     @Value("${security.jwt.ttl-minutes}") long ttlMinutes) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("security.jwt.secret must be at least 32 bytes for HS256");
        }
        try {
            this.signer = new MACSigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid JWT secret", e);
        }
        this.issuer = issuer;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(User user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId())
                .issuer(issuer)
                .claim("name", user.getFullName())
                .claim("email", user.getEmail())
                .claim("accountNumber", user.getAccountNumber())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign token", e);
        }
        return jwt.serialize();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
