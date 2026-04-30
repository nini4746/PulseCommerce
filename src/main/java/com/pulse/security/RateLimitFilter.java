package com.pulse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory IP-based token bucket rate limiter for /auth/login.
 * Defends against credential stuffing / brute force at the MVP level.
 * For production, replace with Redis-backed shared limiter.
 */
@Component
@Order(0)
public class RateLimitFilter extends OncePerRequestFilter {

    private final long capacity;
    private final long refillPerMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${pulse.ratelimit.login.capacity:10}") long capacity,
                           @Value("${pulse.ratelimit.login.refill-per-minute:10}") long refillPerMinute) {
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(req.getMethod()) && "/auth/login".equals(req.getRequestURI())) {
            String ip = clientIp(req);
            Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket(capacity, refillPerMinute));
            if (!b.tryConsume()) {
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                res.setContentType("application/json");
                res.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"login rate limit exceeded\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr() == null ? "unknown" : req.getRemoteAddr();
    }

    private static final class Bucket {
        private final long capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefillNanos;

        Bucket(long capacity, long refillPerMinute) {
            this.capacity = capacity;
            this.refillPerMs = refillPerMinute / 60000.0;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            long elapsedMs = (now - lastRefillNanos) / 1_000_000L;
            if (elapsedMs > 0) {
                tokens = Math.min(capacity, tokens + elapsedMs * refillPerMs);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
