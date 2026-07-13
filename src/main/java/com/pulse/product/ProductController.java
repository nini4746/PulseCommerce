package com.pulse.product;

import com.pulse.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
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

    @GetMapping
    public List<ProductView> list() {
        return service.list();
    }

    @GetMapping("/mine")
    public List<ProductView> mine(@AuthenticationPrincipal AuthPrincipal me) {
        return service.mine(me);
    }

    @GetMapping("/{id}")
    public ProductView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@AuthenticationPrincipal AuthPrincipal me,
                                              @Valid @RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(me, req.name(), req.priceCents(), req.stock()));
    }

    @PatchMapping("/{id}")
    public ProductView update(@AuthenticationPrincipal AuthPrincipal me,
                              @PathVariable Long id,
                              @RequestBody UpdateRequest req) {
        return service.update(me, id, req.name(), req.priceCents(), req.stock());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        service.delete(me, id);
        return ResponseEntity.noContent().build();
    }
}
