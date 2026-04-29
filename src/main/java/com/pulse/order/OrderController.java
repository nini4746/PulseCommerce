package com.pulse.order;

import com.pulse.domain.Order;
import com.pulse.domain.OrderStatus;
import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.event.OrderCancelledEvent;
import com.pulse.event.OrderPlacedEvent;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.security.AuthPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger audit = LoggerFactory.getLogger("audit." + OrderController.class.getName());
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public OrderController(OrderRepository orders, ProductRepository products,
                           ApplicationEventPublisher events) {
        this.orders = orders;
        this.products = products;
        this.events = events;
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
        audit.info("order.placed buyerId={} productId={} qty={} orderId={}",
                me.userId(), p.getId(), req.quantity(), saved.getId());
        events.publishEvent(new OrderPlacedEvent(saved.getId(), me.userId(), p.getId(),
                req.quantity(), saved.totalCents(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderView.of(saved));
    }

    @GetMapping("/me")
    public Map<String, Object> mine(@AuthenticationPrincipal AuthPrincipal me,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "20") int size) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<Order> pageData = orders.findByBuyerIdOrderByCreatedAtDesc(
                me.userId(), PageRequest.of(page, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return Map.of(
                "page", pageData.getNumber(),
                "size", pageData.getSize(),
                "totalElements", pageData.getTotalElements(),
                "totalPages", pageData.getTotalPages(),
                "content", pageData.getContent().stream().map(OrderView::of).toList()
        );
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
        audit.info("order.cancelled buyerId={} orderId={} productId={} qty={}",
                me.userId(), o.getId(), o.getProductId(), o.getQuantity());
        events.publishEvent(new OrderCancelledEvent(o.getId(), me.userId(), o.getProductId(),
                o.getQuantity(), Instant.now()));
        return OrderView.of(o);
    }
}
