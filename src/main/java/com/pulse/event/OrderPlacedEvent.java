package com.pulse.event;

import java.time.Instant;

public record OrderPlacedEvent(
        Long orderId,
        Long buyerId,
        Long productId,
        int quantity,
        long totalCents,
        Instant occurredAt
) {}
