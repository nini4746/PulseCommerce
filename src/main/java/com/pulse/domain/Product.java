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

    @Version
    @Column(nullable = false)
    private long version;

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

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = newName;
    }

    public void changePrice(long newPriceCents) {
        if (newPriceCents < 0) throw new IllegalArgumentException("price must be >= 0");
        this.priceCents = newPriceCents;
    }

    public void setStock(int newStock) {
        if (newStock < 0) throw new IllegalArgumentException("stock must be >= 0");
        this.stock = newStock;
    }

    public void decrementStock(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        if (stock < qty) throw new InsufficientStockException("insufficient stock");
        this.stock -= qty;
    }

    public void restock(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        this.stock += qty;
    }
}
