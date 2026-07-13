package com.pulse.repo;

import com.pulse.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);
    Page<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId, Pageable pageable);
    Page<Order> findByProductIdInOrderByCreatedAtDesc(java.util.Collection<Long> productIds, Pageable pageable);
    java.util.Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);

    /**
     * Orders for the given products created in [from, to). Filters by date in the
     * query itself rather than loading every order for the products into memory.
     */
    @Query("select o from Order o where o.productId in :productIds "
            + "and o.createdAt >= :from and o.createdAt < :to")
    List<Order> findByProductIdInAndCreatedAtRange(@Param("productIds") java.util.Collection<Long> productIds,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to);
}
