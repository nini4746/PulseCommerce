package com.pulse.repo;

import com.pulse.domain.DisputeMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeMessageRepository extends JpaRepository<DisputeMessage, Long> {
    List<DisputeMessage> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
