package com.pulse.product;

import com.pulse.domain.Product;
import com.pulse.domain.Role;
import com.pulse.repo.ProductRepository;
import com.pulse.repo.UserRepository;
import com.pulse.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository products;
    private final UserRepository users;

    public ProductService(ProductRepository products, UserRepository users) {
        this.products = products;
        this.users = users;
    }

    public List<ProductView> list() {
        return products.findAll().stream().map(ProductView::of).toList();
    }

    public List<ProductView> mine(AuthPrincipal me) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        return products.findBySellerId(me.userId()).stream().map(ProductView::of).toList();
    }

    public ProductView get(Long id) {
        return ProductView.of(products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found")));
    }

    @Transactional
    public ProductView create(AuthPrincipal me, String name, long priceCents, int stock) {
        if (me == null || me.role() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller role required");
        }
        var seller = users.findById(me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown seller"));
        if (seller.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "seller suspended");
        }
        Product saved = products.save(new Product(name, priceCents, stock, me.userId()));
        return ProductView.of(saved);
    }

    @Transactional
    public ProductView update(AuthPrincipal me, Long id, String name, Long priceCents, Integer stock) {
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
        if (name != null) p.rename(name);
        if (priceCents != null) p.changePrice(priceCents);
        if (stock != null) p.setStock(stock);
        return ProductView.of(p);
    }

    @Transactional
    public void delete(AuthPrincipal me, Long id) {
        if (me == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Product p = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        if (me.role() != Role.ADMIN && !p.getSellerId().equals(me.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your product");
        }
        products.delete(p);
    }
}
