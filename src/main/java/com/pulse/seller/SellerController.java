package com.pulse.seller;

import com.pulse.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/seller")
public class SellerController {

    private final SellerService service;

    public SellerController(SellerService service) {
        this.service = service;
    }

    @GetMapping("/kpi")
    public Map<String, Object> kpi(@AuthenticationPrincipal AuthPrincipal me,
                                   @RequestParam(name = "from", required = false) String fromStr,
                                   @RequestParam(name = "to", required = false) String toStr) {
        return service.kpi(me, fromStr, toStr);
    }
}
