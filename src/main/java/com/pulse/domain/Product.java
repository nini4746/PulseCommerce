package com.pulse.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long priceCents;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private Long sellerId;

    protected Product() {}

    public Product(String name, long priceCents, int stock, Long sellerId) {
        this.name = name;
        this.priceCents = priceCents;
        this.stock = stock;
        this.sellerId = sellerId;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public long getPriceCents() { return priceCents; }
    public int getStock() { return stock; }
    public Long getSellerId() { return sellerId; }

    public void decrementStock(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        if (stock < qty) throw new IllegalStateException("insufficient stock");
        this.stock -= qty;
    }

    public void restock(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        this.stock += qty;
    }
}
