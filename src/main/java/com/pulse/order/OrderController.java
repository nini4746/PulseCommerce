package com.pulse.order;

import com.pulse.security.AuthPrincipal;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final RefundService refundService;
    private final DisputeService disputeService;

    public OrderController(OrderService orderService, RefundService refundService,
                           DisputeService disputeService) {
        this.orderService = orderService;
        this.refundService = refundService;
        this.disputeService = disputeService;
    }

    public record PlaceRequest(@Min(1) Long productId, @Min(1) int quantity) {}

    public record CancelRequest(String reason, String note) {}

    public record RefundRequest(String action) {}

    public record DisputeMessageRequest(String body) {}

    @PostMapping
    public ResponseEntity<OrderView> place(@AuthenticationPrincipal AuthPrincipal me,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                           @Valid @RequestBody PlaceRequest req) {
        OrderService.PlaceResult result = orderService.place(me, idemKey, req.productId(), req.quantity());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.view());
    }

    @GetMapping("/me")
    public Map<String, Object> mine(@AuthenticationPrincipal AuthPrincipal me,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "20") int size) {
        return orderService.mine(me, page, size);
    }

    @GetMapping("/seller")
    public Map<String, Object> sellerOrders(@AuthenticationPrincipal AuthPrincipal me,
                                            @RequestParam(name = "page", defaultValue = "0") int page,
                                            @RequestParam(name = "size", defaultValue = "20") int size) {
        return orderService.sellerOrders(me, page, size);
    }

    @PostMapping("/{id}/cancel")
    public OrderView cancel(@AuthenticationPrincipal AuthPrincipal me,
                            @PathVariable Long id,
                            @RequestBody(required = false) CancelRequest req) {
        return orderService.cancel(me, id, req == null ? null : req.reason(), req == null ? null : req.note());
    }

    @PostMapping("/{id}/refund")
    public OrderView refund(@AuthenticationPrincipal AuthPrincipal me,
                            @PathVariable Long id,
                            @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                            @RequestBody(required = false) RefundRequest req) {
        return refundService.refund(me, id, idemKey, req == null ? null : req.action());
    }

    @PostMapping("/{id}/pay")
    public OrderView pay(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        return orderService.pay(me, id);
    }

    @PostMapping("/{id}/ship")
    public OrderView ship(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        return orderService.ship(me, id);
    }

    @PostMapping("/{id}/deliver")
    public OrderView deliver(@AuthenticationPrincipal AuthPrincipal me, @PathVariable Long id) {
        return orderService.deliver(me, id);
    }

    @GetMapping("/{id}/dispute/messages")
    public List<DisputeMessageView> listDisputeMessages(@AuthenticationPrincipal AuthPrincipal me,
                                                        @PathVariable Long id) {
        return disputeService.listMessages(me, id);
    }

    @PostMapping("/{id}/dispute/messages")
    public ResponseEntity<DisputeMessageView> postDisputeMessage(@AuthenticationPrincipal AuthPrincipal me,
                                                                 @PathVariable Long id,
                                                                 @RequestBody(required = false) DisputeMessageRequest req) {
        DisputeMessageView view = disputeService.postMessage(me, id, req == null ? null : req.body());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }
}
