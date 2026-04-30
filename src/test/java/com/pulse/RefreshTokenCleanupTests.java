package com.pulse;

import com.pulse.domain.RefreshToken;
import com.pulse.repo.RefreshTokenRepository;
import com.pulse.security.RefreshTokenCleanupJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pulse_rtcl_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pulse.jwt.secret=test-secret-test-secret-test-secret-1234567890",
        "pulse.admin.password=admin12345",
        "pulse.ratelimit.login.capacity=1000",
        "pulse.ratelimit.login.refill-per-minute=1000",
        // Disable scheduled run during tests with valid 6-field cron that won't fire often
        "pulse.refresh.cleanup-cron=0 0 0 29 2 *"
})
class RefreshTokenCleanupTests {

    @Autowired private RefreshTokenRepository repo;
    @Autowired private RefreshTokenCleanupJob job;

    @Test
    void cleanup_deletes_expired_tokens_only() {
        Instant now = Instant.now();
        // expired (1 hour ago)
        RefreshToken expired = repo.save(new RefreshToken(1L, "h-expired", now.minus(1, ChronoUnit.HOURS)));
        // expired (1 day ago)
        RefreshToken expired2 = repo.save(new RefreshToken(2L, "h-expired-2", now.minus(1, ChronoUnit.DAYS)));
        // still valid
        RefreshToken active = repo.save(new RefreshToken(3L, "h-active", now.plus(1, ChronoUnit.HOURS)));

        int removed = job.run();
        assertEquals(2, removed);

        assertTrue(repo.findById(expired.getId()).isEmpty());
        assertTrue(repo.findById(expired2.getId()).isEmpty());
        assertTrue(repo.findById(active.getId()).isPresent());
    }

    @Test
    void cleanup_with_no_expired_returns_zero() {
        Instant now = Instant.now();
        repo.save(new RefreshToken(99L, "h-future", now.plus(7, ChronoUnit.DAYS)));
        assertEquals(0, job.run());
    }
}
