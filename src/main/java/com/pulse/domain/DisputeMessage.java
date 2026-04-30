package com.pulse.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Single message in an order's dispute thread (buyer <-> seller; admin can read).
 */
@Entity
@Table(name = "dispute_messages", indexes = {
        @Index(name = "idx_dispute_order_created", columnList = "orderId,createdAt")
})
public class DisputeMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role senderRole;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    protected DisputeMessage() {}

    public DisputeMessage(Long orderId, Long senderId, Role senderRole, String body) {
        this.orderId = orderId;
        this.senderId = senderId;
        this.senderRole = senderRole;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getSenderId() { return senderId; }
    public Role getSenderRole() { return senderRole; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
