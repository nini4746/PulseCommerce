package com.pulse.admin;

import com.pulse.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/sellers")
    public List<SellerView> listSellers(@AuthenticationPrincipal AuthPrincipal me) {
        return service.listSellers(me);
    }

    @PostMapping("/sellers/{id}/suspend")
    public Map<String, Object> suspendSeller(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        return service.suspendSeller(me, id);
    }

    @PostMapping("/sellers/{id}/unsuspend")
    public Map<String, Object> unsuspendSeller(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        return service.unsuspendSeller(me, id);
    }
}
