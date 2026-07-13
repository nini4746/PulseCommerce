package com.pulse.product;

import com.pulse.domain.Product;

public record ProductView(Long id, String name, long priceCents, int stock, Long sellerId) {
    public static ProductView of(Product p) {
        return new ProductView(p.getId(), p.getName(), p.getPriceCents(), p.getStock(), p.getSellerId());
    }
}
