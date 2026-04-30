package com.pulse.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.domain.CancelReason;
import com.pulse.domain.DisputeMessage;
import com.pulse.domain.Order;
import com.pulse.domain.OrderStatus;
import com.pulse.domain.Product;
import com.pulse.domain.RefundIdempotency;
import com.pulse.domain.RefundStatus;
import com.pulse.domain.Role;
import com.pulse.event.OrderCancelledEvent;
import com.pulse.event.OrderPlacedEvent;
import com.pulse.repo.DisputeMessageRepository;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.repo.RefundIdempotencyRepository;
import com.pulse.security.AuthPrincipal;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final RefundIdempotencyRepository refundIdem;
    private final DisputeMessageRepository disputes;
    private final ObjectMapper json;

    public OrderController(OrderRepository orders, ProductRepository products,
                           ApplicationEventPublisher events,
                           RefundIdempotencyRepository refundIdem,
                           DisputeMessageRepository disputes,
                           ObjectMapper json) {
        this.orders = orders;
        this.products = products;
        this.events = events;
        this.refundIdem = refundIdem;
        this.disputes = disputes;
        this.json = json;
    }

    public record PlaceRequest(@Min(1) Long productId, @Min(1) int quantity) {}

    public record CancelRequest(String reason, String note) {}

    public record RefundRequest(String action) {}

    public record DisputeMessageRequest(String body) {}

    public record DisputeMessageView(Long id, Long orderId, Long senderId, Role senderRole,
                                     String body, Instant createdAt) {
        static DisputeMessageView of(DisputeMessage m) {
            return new DisputeMessageView(m.getId(), m.getOrderId(), m.getSenderId(),
                    m.getSenderRole(), m.getBody(), m.getCreatedAt());
        }
    }

    public record OrderView(Long id, Long buyerId, Long productId, int quantity,
                            long unitPriceCents, long totalCents, OrderStatus status,
                            CancelReason cancelReason, String cancelNote, RefundStatus refundStatus,
                            Instant createdAt) {
        static OrderView of(Order o) {
            return new OrderView(o.getId(), o.getBuyerId(), o.getProductId(), o.getQuantity(),
                    o.getUnitPriceCents(), o.totalCents(), o.getStatus(),
                    o.getCancelReason(), o.getCancelNote(), o.getRefundStatus(),
                    o.getCreatedAt());
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<OrderView> place(@AuthenticationPrincipal AuthPrincipal me,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                           @Valid @RequestBody PlaceRequest req) {
        if (me == null || me.role() != Role.BUYER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "buyer role required");
        }
        if (idemKey != null && !idemKey.isBlank()) {
            if (idemKey.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long (max 64)");
            }
            var existing = orders.findByBuyerIdAndIdempotencyKey(me.userId(), idemKey);
            if (existing.isPresent()) {
                return ResponseEntity.status(HttpStatus.OK).body(OrderView.of(existing.get()));
            }
        }
        Product p = products.findById(req.productId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot order your own product");
        }
        p.decrementStock(req.quantity());
        Order saved = orders.save(new Order(me.userId(), p.getId(), req.quantity(), p.getPriceCents(),
                (idemKey == null || idemKey.isBlank()) ? null : idemKey));
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

    @GetMapping("/seller")
    public Map<String, Object> sellerOrders(@AuthenticationPrincipal AuthPrincipal me,
                                            @RequestParam(name = "page", defaultValue = "0") int page,
                                            @RequestParam(name = "size", defaultValue = "20") int size) {
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

    @PostMapping("/{id}/cancel")
    @Transactional
    public OrderView cancel(@AuthenticationPrincipal AuthPrincipal me,
                            @PathVariable Long id,
                            @RequestBody(required = false) CancelRequest req) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!o.getBuyerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your order");
        }
        CancelReason reason = parseReason(req == null ? null : req.reason());
        String note = req == null ? null : req.note();
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

    @PostMapping("/{id}/refund")
    @Transactional
    public OrderView refund(@AuthenticationPrincipal AuthPrincipal me,
                            @PathVariable Long id,
                            @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                            @RequestBody(required = false) RefundRequest req) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (idemKey != null && !idemKey.isBlank()) {
            if (idemKey.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long (max 64)");
            }
            var cached = refundIdem.findByActorIdAndIdempotencyKey(me.userId(), idemKey);
            if (cached.isPresent()) {
                if (!cached.get().getOrderId().equals(id)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Idempotency-Key already used for a different order");
                }
                try {
                    return json.readValue(cached.get().getResponseJson(), OrderView.class);
                } catch (JsonProcessingException e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "cached idempotency response unreadable");
                }
            }
        }
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        Product p = products.findById(o.getProductId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        boolean isAdmin = me.role() == Role.ADMIN;
        boolean isSellerOfProduct = me.role() == Role.SELLER && p.getSellerId().equals(me.userId());
        if (!isAdmin && !isSellerOfProduct) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller of product or admin required");
        }
        String action = (req == null || req.action() == null) ? "" : req.action().toUpperCase();
        switch (action) {
            case "APPROVE" -> o.approveRefund();
            case "REJECT" -> o.rejectRefund();
            case "REFUND" -> o.completeRefund();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action must be APPROVE, REJECT, or REFUND");
        }
        audit.info("order.refund actorId={} role={} orderId={} action={} status={} idem={}",
                me.userId(), me.role(), o.getId(), action, o.getRefundStatus(),
                idemKey == null ? "-" : idemKey);
        OrderView view = OrderView.of(o);
        if (idemKey != null && !idemKey.isBlank()) {
            try {
                refundIdem.save(new RefundIdempotency(me.userId(), idemKey, o.getId(), json.writeValueAsString(view)));
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to cache idempotency response");
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Concurrent insert with same key — fall through; client retry will hit cache.
            }
        }
        return view;
    }

    @PostMapping("/{id}/pay")
    @Transactional
    public OrderView pay(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
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

    @PostMapping("/{id}/ship")
    @Transactional
    public OrderView ship(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
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

    @PostMapping("/{id}/deliver")
    @Transactional
    public OrderView deliver(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
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

    @GetMapping("/{id}/dispute/messages")
    public List<DisputeMessageView> listDisputeMessages(@AuthenticationPrincipal AuthPrincipal me,
                                                       @PathVariable Long id) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        ensureDisputeAccess(me, o);
        return disputes.findByOrderIdOrderByCreatedAtAsc(id).stream()
                .map(DisputeMessageView::of).toList();
    }

    @PostMapping("/{id}/dispute/messages")
    @Transactional
    public ResponseEntity<DisputeMessageView> postDisputeMessage(@AuthenticationPrincipal AuthPrincipal me,
                                                                 @PathVariable Long id,
                                                                 @RequestBody(required = false) DisputeMessageRequest req) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        // Admin can read but not post (read-only oversight)
        if (me.role() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin can read but not post dispute messages");
        }
        ensureDisputeAccess(me, o);
        String body = req == null ? null : req.body();
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        if (body.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body too long (max 2000)");
        }
        DisputeMessage saved = disputes.save(new DisputeMessage(id, me.userId(), me.role(), body));
        audit.info("dispute.message.posted orderId={} senderId={} role={} msgId={}",
                id, me.userId(), me.role(), saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DisputeMessageView.of(saved));
    }

    private void ensureDisputeAccess(AuthPrincipal me, Order o) {
        if (me.role() == Role.ADMIN) return;
        if (me.role() == Role.BUYER && o.getBuyerId().equals(me.userId())) return;
        if (me.role() == Role.SELLER) {
            Product p = products.findById(o.getProductId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
            if (p.getSellerId().equals(me.userId())) return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a participant of this order");
    }

    private static CancelReason parseReason(String s) {
        if (s == null || s.isBlank()) return CancelReason.OTHER;
        try {
            return CancelReason.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cancel reason: " + s);
        }
    }
}
