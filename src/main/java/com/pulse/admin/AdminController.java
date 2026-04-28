package com.pulse.admin;

import com.pulse.domain.Role;
import com.pulse.domain.User;
import com.pulse.repo.UserRepository;
import com.pulse.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository users;

    public AdminController(UserRepository users) {
        this.users = users;
    }

    @PostMapping("/sellers/{id}/suspend")
    public Map<String, Object> suspendSeller(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        if (me == null || me.role() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
        User target = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (target.getRole() != Role.SELLER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user is not a seller");
        }
        target.suspend();
        users.save(target);
        return Map.of("id", target.getId(), "suspended", true);
    }
}
