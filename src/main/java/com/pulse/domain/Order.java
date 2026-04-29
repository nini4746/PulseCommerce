package com.pulse.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "orders")
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

    @Version
    @Column(nullable = false)
    private long version;

    protected Order() {}

    public Order(Long buyerId, Long productId, int quantity, long unitPriceCents) {
        this.buyerId = buyerId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.status = OrderStatus.PLACED;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getBuyerId() { return buyerId; }
    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public long getUnitPriceCents() { return unitPriceCents; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public long totalCents() { return unitPriceCents * quantity; }

    public void cancel() {
        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException("only PLACED orders can be cancelled");
        }
        this.status = OrderStatus.CANCELLED;
    }
}
