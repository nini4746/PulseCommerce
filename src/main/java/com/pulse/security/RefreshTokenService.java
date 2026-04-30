package com.pulse.security;

import com.pulse.domain.RefreshToken;
import com.pulse.repo.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Refresh token issuance/rotation/revocation.
 * Tokens are random 256-bit strings; only SHA-256 hashes are persisted.
 * Each /auth/refresh rotates: previous token is revoked, a new one is issued.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository repo;
    private final long ttlMillis;

    public RefreshTokenService(RefreshTokenRepository repo,
                               @Value("${pulse.refresh.ttl-ms:1209600000}") long ttlMillis) {
        this.repo = repo;
        this.ttlMillis = ttlMillis;
    }

    @Transactional
    public Issued issue(Long userId) {
        String raw = generate();
        String hash = sha256(raw);
        Instant exp = Instant.now().plusMillis(ttlMillis);
        RefreshToken saved = repo.save(new RefreshToken(userId, hash, exp));
        return new Issued(saved.getId(), raw, exp);
    }

    @Transactional
    public Optional<RefreshToken> consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String hash = sha256(rawToken);
        Optional<RefreshToken> opt = repo.findByTokenHash(hash);
        if (opt.isEmpty()) return Optional.empty();
        RefreshToken rt = opt.get();
        if (!rt.isUsable(Instant.now())) return Optional.empty();
        rt.revoke();
        return Optional.of(rt);
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        return repo.revokeAllForUser(userId);
    }

    public record Issued(Long id, String token, Instant expiresAt) {}

    private static String generate() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return URL.encodeToString(buf);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return URL.encodeToString(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
