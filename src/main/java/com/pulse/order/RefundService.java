package com.pulse.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.domain.Order;
import com.pulse.domain.Product;
import com.pulse.domain.RefundIdempotency;
import com.pulse.domain.Role;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.repo.RefundIdempotencyRepository;
import com.pulse.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundService {

    private static final Logger audit = LoggerFactory.getLogger("audit." + RefundService.class.getName());

    private final OrderRepository orders;
    private final ProductRepository products;
    private final RefundIdempotencyRepository refundIdem;
    private final ObjectMapper json;

    public RefundService(OrderRepository orders, ProductRepository products,
                         RefundIdempotencyRepository refundIdem, ObjectMapper json) {
        this.orders = orders;
        this.products = products;
        this.refundIdem = refundIdem;
        this.json = json;
    }

    @Transactional
    public OrderView refund(AuthPrincipal me, Long id, String idemKey, String actionRaw) {
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
        String action = (actionRaw == null) ? "" : actionRaw.toUpperCase();
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
}
