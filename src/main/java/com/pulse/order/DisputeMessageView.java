package com.pulse.order;

import com.pulse.domain.DisputeMessage;
import com.pulse.domain.Role;

import java.time.Instant;

public record DisputeMessageView(Long id, Long orderId, Long senderId, Role senderRole,
                                 String body, Instant createdAt) {
    public static DisputeMessageView of(DisputeMessage m) {
        return new DisputeMessageView(m.getId(), m.getOrderId(), m.getSenderId(),
                m.getSenderRole(), m.getBody(), m.getCreatedAt());
    }
}
