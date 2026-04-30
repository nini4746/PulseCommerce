package com.pulse.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Idempotency record for refund operations.
 * Same (actorId, idempotencyKey) returns the cached response body.
 */
@Entity
@Table(name = "refund_idempotency", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_idem_actor_key", columnNames = {"actorId", "idempotencyKey"})
})
public class RefundIdempotency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long actorId;

    @Column(nullable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long orderId;

    @Lob
    @Column(nullable = false)
    private String responseJson;

    @Column(nullable = false)
    private Instant createdAt;

    protected RefundIdempotency() {}

    public RefundIdempotency(Long actorId, String idempotencyKey, Long orderId, String responseJson) {
        this.actorId = actorId;
        this.idempotencyKey = idempotencyKey;
        this.orderId = orderId;
        this.responseJson = responseJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getActorId() { return actorId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getOrderId() { return orderId; }
    public String getResponseJson() { return responseJson; }
    public Instant getCreatedAt() { return createdAt; }
}
