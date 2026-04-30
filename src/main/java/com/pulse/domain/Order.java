package com.pulse.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_orders_buyer_idem", columnNames = {"buyerId", "idempotencyKey"})
})
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buyerId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long unitPriceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CancelReason cancelReason;

    @Column(length = 255)
    private String cancelNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefundStatus refundStatus = RefundStatus.NONE;

    @Version
    @Column(nullable = false)
    private long version;

    protected Order() {}

    public Order(Long buyerId, Long productId, int quantity, long unitPriceCents) {
        this(buyerId, productId, quantity, unitPriceCents, null);
    }

    public Order(Long buyerId, Long productId, int quantity, long unitPriceCents, String idempotencyKey) {
        this.buyerId = buyerId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.status = OrderStatus.PLACED;
        this.createdAt = Instant.now();
        this.idempotencyKey = idempotencyKey;
        this.refundStatus = RefundStatus.NONE;
    }

    public String getIdempotencyKey() { return idempotencyKey; }

    public Long getId() { return id; }
    public Long getBuyerId() { return buyerId; }
    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public long getUnitPriceCents() { return unitPriceCents; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public CancelReason getCancelReason() { return cancelReason; }
    public String getCancelNote() { return cancelNote; }
    public RefundStatus getRefundStatus() { return refundStatus; }

    public long totalCents() { return unitPriceCents * quantity; }

    public void cancel(CancelReason reason, String note) {
        if (status != OrderStatus.PLACED && status != OrderStatus.PAID) {
            throw new IllegalOrderStateException("only PLACED or PAID orders can be cancelled");
        }
        boolean wasPaid = status == OrderStatus.PAID;
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason == null ? CancelReason.OTHER : reason;
        this.cancelNote = note;
        if (wasPaid && refundStatus == RefundStatus.NONE) {
            this.refundStatus = RefundStatus.REQUESTED;
        }
    }

    /** Legacy callers without a reason. */
    public void cancel() {
        cancel(CancelReason.OTHER, null);
    }

    public void approveRefund() {
        if (status != OrderStatus.CANCELLED) {
            throw new IllegalOrderStateException("refund only on CANCELLED orders");
        }
        if (refundStatus != RefundStatus.REQUESTED) {
            throw new IllegalOrderStateException("refund must be REQUESTED to approve, got " + refundStatus);
        }
        this.refundStatus = RefundStatus.APPROVED;
    }

    public void rejectRefund() {
        if (refundStatus != RefundStatus.REQUESTED) {
            throw new IllegalOrderStateException("refund must be REQUESTED to reject, got " + refundStatus);
        }
        this.refundStatus = RefundStatus.REJECTED;
    }

    public void completeRefund() {
        if (refundStatus != RefundStatus.APPROVED) {
            throw new IllegalOrderStateException("refund must be APPROVED to complete, got " + refundStatus);
        }
        this.refundStatus = RefundStatus.REFUNDED;
    }

    public void markPaid() {
        if (status != OrderStatus.PLACED) {
            throw new IllegalOrderStateException("only PLACED orders can be paid");
        }
        this.status = OrderStatus.PAID;
    }

    public void markShipped() {
        if (status != OrderStatus.PAID) {
            throw new IllegalOrderStateException("only PAID orders can be shipped");
        }
        this.status = OrderStatus.SHIPPED;
    }

    public void markDelivered() {
        if (status != OrderStatus.SHIPPED) {
            throw new IllegalOrderStateException("only SHIPPED orders can be delivered");
        }
        this.status = OrderStatus.DELIVERED;
    }
}
