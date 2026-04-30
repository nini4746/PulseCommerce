package com.pulse.seller;

import com.pulse.domain.Order;
import com.pulse.domain.OrderStatus;
import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.repo.OrderRepository;
import com.pulse.repo.ProductRepository;
import com.pulse.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/seller")
public class SellerController {

    private final ProductRepository products;
    private final OrderRepository orders;

    public SellerController(ProductRepository products, OrderRepository orders) {
        this.products = products;
        this.orders = orders;
    }

    @GetMapping("/kpi")
    public Map<String, Object> kpi(@AuthenticationPrincipal AuthPrincipal me) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        List<Long> productIds = products.findBySellerId(me.userId()).stream().map(Product::getId).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            body.put("orderCount", 0L);
            body.put("cancelledCount", 0L);
            body.put("gmvCents", 0L);
            body.put("cancelRate", 0.0);
            return body;
        }
        List<Order> ordersForSeller = orders.findByProductIdIn(productIds);
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
}
