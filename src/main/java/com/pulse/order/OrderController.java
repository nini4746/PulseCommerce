package com.pulse.order;

import com.pulse.domain.Order;
import com.pulse.domain.OrderStatus;
import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orders;
    private final ProductRepository products;

    public OrderController(OrderRepository orders, ProductRepository products) {
        this.orders = orders;
        this.products = products;
    }

    public record PlaceRequest(@Min(1) Long productId, @Min(1) int quantity) {}

    public record OrderView(Long id, Long buyerId, Long productId, int quantity,
                            long unitPriceCents, long totalCents, OrderStatus status, Instant createdAt) {
        static OrderView of(Order o) {
            return new OrderView(o.getId(), o.getBuyerId(), o.getProductId(), o.getQuantity(),
                    o.getUnitPriceCents(), o.totalCents(), o.getStatus(), o.getCreatedAt());
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<OrderView> place(@AuthenticationPrincipal AuthPrincipal me,
                                           @Valid @RequestBody PlaceRequest req) {
        if (me == null || me.role() != Role.BUYER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "buyer role required");
        }
        Product p = products.findById(req.productId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot order your own product");
        }
        p.decrementStock(req.quantity());
        Order saved = orders.save(new Order(me.userId(), p.getId(), req.quantity(), p.getPriceCents()));
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderView.of(saved));
    }

    @GetMapping("/me")
    public List<OrderView> mine(@AuthenticationPrincipal AuthPrincipal me) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return orders.findByBuyerIdOrderByCreatedAtDesc(me.userId()).stream().map(OrderView::of).toList();
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public OrderView cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!o.getBuyerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your order");
        }
        o.cancel();
        products.findById(o.getProductId()).ifPresent(p -> p.restock(o.getQuantity()));
        return OrderView.of(o);
    }
}
