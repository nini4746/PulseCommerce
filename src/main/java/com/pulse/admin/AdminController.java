package com.pulse.admin;

import com.pulse.domain.Role;
import com.pulse.domain.User;
import com.pulse.repo.UserRepository;
import com.pulse.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger audit = LoggerFactory.getLogger("audit." + AdminController.class.getName());

    private final UserRepository users;

    public AdminController(UserRepository users) {
        this.users = users;
    }

    public record SellerView(Long id, String email, boolean suspended) {
        static SellerView of(User u) { return new SellerView(u.getId(), u.getEmail(), u.isSuspended()); }
    }

    private void requireAdmin(AuthPrincipal me) {
        if (me == null || me.role() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
    }

    @GetMapping("/sellers")
    public List<SellerView> listSellers(@AuthenticationPrincipal AuthPrincipal me) {
        requireAdmin(me);
        return users.findByRole(Role.SELLER).stream().map(SellerView::of).toList();
    }

    @PostMapping("/sellers/{id}/suspend")
    public Map<String, Object> suspendSeller(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        requireAdmin(me);
        User target = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (target.getRole() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user is not a seller");
        }
        target.suspend();
        users.save(target);
        audit.info("admin.seller.suspend adminId={} sellerId={}", me.userId(), target.getId());
        return Map.of("id", target.getId(), "suspended", true);
    }

    @PostMapping("/sellers/{id}/unsuspend")
    public Map<String, Object> unsuspendSeller(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        requireAdmin(me);
        User target = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (target.getRole() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user is not a seller");
        }
        target.unsuspend();
        users.save(target);
        audit.info("admin.seller.unsuspend adminId={} sellerId={}", me.userId(), target.getId());
        return Map.of("id", target.getId(), "suspended", false);
    }
}
