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

    public record UpdateRequest(
            String name,
            Long priceCents,
            Integer stock
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

    @GetMapping("/mine")
    public List<ProductView> mine(@AuthenticationPrincipal AuthPrincipal me) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        return products.findBySellerId(me.userId()).stream().map(ProductView::of).toList();
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

    @org.springframework.transaction.annotation.Transactional
    @PatchMapping("/{id}")
    public ProductView update(@AuthenticationPrincipal AuthPrincipal me,
                              @PathVariable Long id,
                              @RequestBody UpdateRequest req) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        Product p = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (!p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your product");
        }
        var seller = users.findById(me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown seller"));
        if (seller.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller suspended");
        }
        if (req.name() != null) p.rename(req.name());
        if (req.priceCents() != null) p.changePrice(req.priceCents());
        if (req.stock() != null) p.setStock(req.stock());
        return ProductView.of(p);
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Product p = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (me.role() != Role.ADMIN && !p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your product");
        }
        products.delete(p);
        return ResponseEntity.noContent().build();
    }
}
