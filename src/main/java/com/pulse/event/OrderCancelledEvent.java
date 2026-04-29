package com.pulse.event;

import java.time.Instant;

public record OrderCancelledEvent(
        Long orderId,
        Long buyerId,
        Long productId,
        int restockQuantity,
        Instant occurredAt
) {}
