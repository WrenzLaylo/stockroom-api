package dev.wrenz.stockroom.stock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    Page<StockMovement> findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(long shopId, long productId, Pageable pageable);
}
