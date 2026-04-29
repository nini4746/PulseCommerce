package com.pulse.security;

import com.pulse.domain.Role;
import com.pulse.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${pulse.jwt.secret:}") String secret,
                      @Value("${pulse.jwt.ttl-ms:3600000}") long ttlMillis) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "pulse.jwt.secret (JWT_SECRET) is not set; refusing to start without an explicit JWT secret");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "pulse.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMillis = ttlMillis;
    }

    public String issue(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    public AuthPrincipal parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims c = jws.getPayload();
        return new AuthPrincipal(
                Long.parseLong(c.getSubject()),
                c.get("email", String.class),
                Role.valueOf(c.get("role", String.class))
        );
    }
}
