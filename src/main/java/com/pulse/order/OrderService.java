package com.pulse.order;

import com.pulse.domain.CancelReason;
import com.pulse.domain.Order;
import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.event.OrderCancelledEvent;
import com.pulse.event.OrderPlacedEvent;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private static final Logger audit = LoggerFactory.getLogger("audit." + OrderService.class.getName());
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, ProductRepository products,
                        ApplicationEventPublisher events) {
        this.orders = orders;
        this.products = products;
        this.events = events;
    }

    @Transactional
    public PlaceResult place(AuthPrincipal me, String idemKey, Long productId, int quantity) {
        if (me == null || me.role() != Role.BUYER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "buyer role required");
        }
        if (idemKey != null && !idemKey.isBlank()) {
            if (idemKey.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long (max 64)");
            }
            var existing = orders.findByBuyerIdAndIdempotencyKey(me.userId(), idemKey);
            if (existing.isPresent()) {
                return new PlaceResult(OrderView.of(existing.get()), false);
            }
        }
        Product p = products.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot order your own product");
        }
        p.decrementStock(quantity);
        Order saved = orders.save(new Order(me.userId(), p.getId(), quantity, p.getPriceCents(),
                (idemKey == null || idemKey.isBlank()) ? null : idemKey));
        audit.info("order.placed buyerId={} productId={} qty={} orderId={}",
                me.userId(), p.getId(), quantity, saved.getId());
        events.publishEvent(new OrderPlacedEvent(saved.getId(), me.userId(), p.getId(),
                quantity, saved.totalCents(), Instant.now()));
        return new PlaceResult(OrderView.of(saved), true);
    }

    public Map<String, Object> mine(AuthPrincipal me, int page, int size) {
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

    public Map<String, Object> sellerOrders(AuthPrincipal me, int page, int size) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<Long> myProductIds = products.findBySellerId(me.userId()).stream().map(Product::getId).toList();
        if (myProductIds.isEmpty()) {
            return Map.of("page", 0, "size", boundedSize, "totalElements", 0L,
                    "totalPages", 0, "content", List.of());
        }
        Page<Order> pageData = orders.findByProductIdInOrderByCreatedAtDesc(
                myProductIds, PageRequest.of(page, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return Map.of(
                "page", pageData.getNumber(),
                "size", pageData.getSize(),
                "totalElements", pageData.getTotalElements(),
                "totalPages", pageData.getTotalPages(),
                "content", pageData.getContent().stream().map(OrderView::of).toList()
        );
    }

    @Transactional
    public OrderView cancel(AuthPrincipal me, Long id, String reasonStr, String note) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!o.getBuyerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your order");
        }
        CancelReason reason = parseReason(reasonStr);
        if (note != null && note.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note too long (max 255)");
        }
        o.cancel(reason, note);
        products.findById(o.getProductId()).ifPresent(p -> p.restock(o.getQuantity()));
        audit.info("order.cancelled buyerId={} orderId={} productId={} qty={} reason={} refund={}",
                me.userId(), o.getId(), o.getProductId(), o.getQuantity(),
                o.getCancelReason(), o.getRefundStatus());
        events.publishEvent(new OrderCancelledEvent(o.getId(), me.userId(), o.getProductId(),
                o.getQuantity(), Instant.now()));
        return OrderView.of(o);
    }

    @Transactional
    public OrderView pay(AuthPrincipal me, Long id) {
        if (me == null || me.role() != Role.BUYER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "buyer role required");
        }
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!o.getBuyerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your order");
        }
        o.markPaid();
        audit.info("order.paid buyerId={} orderId={}", me.userId(), o.getId());
        return OrderView.of(o);
    }

    @Transactional
    public OrderView ship(AuthPrincipal me, Long id) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        Product p = products.findById(o.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (!p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your product");
        }
        o.markShipped();
        audit.info("order.shipped sellerId={} orderId={}", me.userId(), o.getId());
        return OrderView.of(o);
    }

    @Transactional
    public OrderView deliver(AuthPrincipal me, Long id) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        Product p = products.findById(o.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (!p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your product");
        }
        o.markDelivered();
        audit.info("order.delivered sellerId={} orderId={}", me.userId(), o.getId());
        return OrderView.of(o);
    }

    private static CancelReason parseReason(String s) {
        if (s == null || s.isBlank()) return CancelReason.OTHER;
        try {
            return CancelReason.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cancel reason: " + s);
        }
    }

    /** Result of placing an order: the view plus whether it was newly created (201) or an idempotent replay (200). */
    public record PlaceResult(OrderView view, boolean created) {}
}
