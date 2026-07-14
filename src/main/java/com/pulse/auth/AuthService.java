package com.pulse.auth;

import com.pulse.domain.RefreshToken;
import com.pulse.domain.Role;
import com.pulse.domain.User;
import com.pulse.repo.UserRepository;
import com.pulse.security.AuthPrincipal;
import com.pulse.security.JwtService;
import com.pulse.security.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;

    public AuthService(UserRepository users, PasswordEncoder encoder,
                       JwtService jwt, RefreshTokenService refresh) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refresh = refresh;
    }

    @Transactional
    public Map<String, Object> signup(String email, String password, String roleRaw) {
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        Role role;
        try {
            role = Role.valueOf(roleRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid role");
        }
        if (role == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin signup not allowed");
        }
        User u = users.save(new User(email, encoder.encode(password), role));
        return Map.of(
                "id", u.getId(),
                "email", u.getEmail(),
                "role", u.getRole().name()
        );
    }

    public Map<String, Object> login(String email, String password) {
        User u = users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        if (!encoder.matches(password, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        if (u.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account suspended");
        }
        return tokenResponse(u);
    }

    public Map<String, Object> refresh(String rawToken) {
        RefreshToken rt = refresh.consume(rawToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token"));
        User u = users.findById(rt.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (u.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account suspended");
        }
        return tokenResponse(u);
    }

    public Map<String, Object> logout(AuthPrincipal me) {
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
        }
        int revoked = refresh.revokeAllForUser(me.userId());
        return Map.of("revoked", revoked);
    }

    private Map<String, Object> tokenResponse(User u) {
        String access = jwt.issue(u);
        RefreshTokenService.Issued issued = refresh.issue(u.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", access); // back-compat alias
        body.put("accessToken", access);
        body.put("refreshToken", issued.token());
        body.put("refreshTokenExpiresAt", issued.expiresAt().toString());
        body.put("role", u.getRole().name());
        body.put("userId", u.getId());
        return body;
    }
}
