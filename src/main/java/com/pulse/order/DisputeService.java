package com.pulse.order;

import com.pulse.domain.DisputeMessage;
import com.pulse.domain.Order;
import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.repo.DisputeMessageRepository;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class DisputeService {

    private static final Logger audit = LoggerFactory.getLogger("audit." + DisputeService.class.getName());

    private final OrderRepository orders;
    private final ProductRepository products;
    private final DisputeMessageRepository disputes;

    public DisputeService(OrderRepository orders, ProductRepository products,
                          DisputeMessageRepository disputes) {
        this.orders = orders;
        this.products = products;
        this.disputes = disputes;
    }

    public List<DisputeMessageView> listMessages(AuthPrincipal me, Long id) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        ensureDisputeAccess(me, o);
        return disputes.findByOrderIdOrderByCreatedAtAsc(id).stream()
                .map(DisputeMessageView::of).toList();
    }

    @Transactional
    public DisputeMessageView postMessage(AuthPrincipal me, Long id, String body) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Order o = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        // Admin can read but not post (read-only oversight)
        if (me.role() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin can read but not post dispute messages");
        }
        ensureDisputeAccess(me, o);
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        if (body.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body too long (max 2000)");
        }
        DisputeMessage saved = disputes.save(new DisputeMessage(id, me.userId(), me.role(), body));
        audit.info("dispute.message.posted orderId={} senderId={} role={} msgId={}",
                id, me.userId(), me.role(), saved.getId());
        return DisputeMessageView.of(saved);
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
}
