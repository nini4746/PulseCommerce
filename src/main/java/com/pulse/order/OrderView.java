package com.pulse.order;

import com.pulse.domain.CancelReason;
import com.pulse.domain.Order;
import com.pulse.domain.OrderStatus;
import com.pulse.domain.RefundStatus;

import java.time.Instant;

public record OrderView(Long id, Long buyerId, Long productId, int quantity,
                        long unitPriceCents, long totalCents, OrderStatus status,
                        CancelReason cancelReason, String cancelNote, RefundStatus refundStatus,
                        Instant createdAt) {
    public static OrderView of(Order o) {
        return new OrderView(o.getId(), o.getBuyerId(), o.getProductId(), o.getQuantity(),
                o.getUnitPriceCents(), o.totalCents(), o.getStatus(),
                o.getCancelReason(), o.getCancelNote(), o.getRefundStatus(),
                o.getCreatedAt());
    }
}
