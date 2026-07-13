package com.pulse.seller;

import com.pulse.domain.Order;
import com.pulse.domain.OrderStatus;
import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SellerService {

    private final ProductRepository products;
    private final OrderRepository orders;

    public SellerService(ProductRepository products, OrderRepository orders) {
        this.products = products;
        this.orders = orders;
    }

    public Map<String, Object> kpi(AuthPrincipal me, String fromStr, String toStr) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        Instant now = Instant.now();
        Instant to = parseInstant(toStr, "to", now);
        Instant from = parseInstant(fromStr, "from", to.minus(30, ChronoUnit.DAYS));
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        List<Long> productIds = products.findBySellerId(me.userId()).stream().map(Product::getId).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from.toString());
        body.put("to", to.toString());
        if (productIds.isEmpty()) {
            body.put("orderCount", 0L);
            body.put("cancelledCount", 0L);
            body.put("gmvCents", 0L);
            body.put("cancelRate", 0.0);
            return body;
        }
        List<Order> ordersForSeller = orders.findByProductIdInAndCreatedAtRange(productIds, from, to);
        long total = ordersForSeller.size();
        long cancelled = ordersForSeller.stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED).count();
        long gmvCents = ordersForSeller.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED)
                .mapToLong(Order::totalCents)
                .sum();
        double cancelRate = total == 0 ? 0.0 : (double) cancelled / (double) total;
        body.put("orderCount", total);
        body.put("cancelledCount", cancelled);
        body.put("gmvCents", gmvCents);
        body.put("cancelRate", Math.round(cancelRate * 10000.0) / 10000.0);
        return body;
    }

    private static Instant parseInstant(String s, String field, Instant fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid ISO-8601 instant for '" + field + "': " + s);
        }
    }
}
