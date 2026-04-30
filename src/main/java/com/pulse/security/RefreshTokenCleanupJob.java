package com.pulse.security;

import com.pulse.repo.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically deletes refresh tokens whose expiry is in the past.
 * Runs daily at 03:30 server time. Safe to invoke manually for tests.
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository repo;

    public RefreshTokenCleanupJob(RefreshTokenRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "${pulse.refresh.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public int run() {
        Instant now = Instant.now();
        int removed = repo.deleteExpiredBefore(now);
        if (removed > 0) {
            log.info("refresh.cleanup removed={} cutoff={}", removed, now);
        }
        return removed;
    }
}
