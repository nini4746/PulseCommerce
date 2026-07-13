package com.pulse.admin;

import com.pulse.domain.Role;
import com.pulse.domain.User;
import com.pulse.repo.UserRepository;
import com.pulse.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class AdminService {

    private static final Logger audit = LoggerFactory.getLogger("audit." + AdminService.class.getName());

    private final UserRepository users;

    public AdminService(UserRepository users) {
        this.users = users;
    }

    public List<SellerView> listSellers(AuthPrincipal me) {
        requireAdmin(me);
        return users.findByRole(Role.SELLER).stream().map(SellerView::of).toList();
    }

    @Transactional
    public Map<String, Object> suspendSeller(AuthPrincipal me, Long id) {
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

    @Transactional
    public Map<String, Object> unsuspendSeller(AuthPrincipal me, Long id) {
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

    private void requireAdmin(AuthPrincipal me) {
        if (me == null || me.role() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
    }
}
