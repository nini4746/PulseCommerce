package com.pulse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    private final JwtService jwt;

    public JwtFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = req.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        res.setHeader("X-Request-Id", requestId);

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthPrincipal p = jwt.parse(token);
                var auth = new UsernamePasswordAuthenticationToken(
                        p, null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.warn("invalid JWT path={} tokenPrefix={} reason={}",
                        req.getRequestURI(), preview(token), e.getClass().getSimpleName());
            }
        }

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
        }
    }

    private static String preview(String token) {
        if (token.length() <= 8) return "***";
        return token.substring(0, 8) + "...";
    }
}
