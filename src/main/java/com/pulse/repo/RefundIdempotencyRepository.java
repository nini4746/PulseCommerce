package com.pulse.repo;

import com.pulse.domain.RefundIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefundIdempotencyRepository extends JpaRepository<RefundIdempotency, Long> {
    Optional<RefundIdempotency> findByActorIdAndIdempotencyKey(Long actorId, String idempotencyKey);
}
