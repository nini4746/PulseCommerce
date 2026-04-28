package com.pulse.product;

import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.repo.ProductRepository;
import com.pulse.repo.UserRepository;
import com.pulse.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductRepository products;
    private final UserRepository users;

    public ProductController(ProductRepository products, UserRepository users) {
        this.products = products;
        this.users = users;
    }

    public record CreateRequest(
            @NotBlank String name,
            @Min(0) long priceCents,
            @Min(0) int stock
    ) {}

    public record ProductView(Long id, String name, long priceCents, int stock, Long sellerId) {
        static ProductView of(Product p) {
            return new ProductView(p.getId(), p.getName(), p.getPriceCents(), p.getStock(), p.getSellerId());
        }
    }

    @GetMapping
    public List<ProductView> list() {
        return products.findAll().stream().map(ProductView::of).toList();
    }

    @GetMapping("/{id}")
    public ProductView get(@PathVariable Long id) {
        return ProductView.of(products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found")));
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@AuthenticationPrincipal AuthPrincipal me,
                                              @Valid @RequestBody CreateRequest req) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        var seller = users.findById(me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown seller"));
        if (seller.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller suspended");
        }
        Product saved = products.save(new Product(req.name(), req.priceCents(), req.stock(), me.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductView.of(saved));
    }
}
