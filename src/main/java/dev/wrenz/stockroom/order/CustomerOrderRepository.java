package dev.wrenz.stockroom.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByIdAndShopId(long id, long shopId);

    Page<CustomerOrder> findByShopIdOrderByCreatedAtDescIdDesc(long shopId, Pageable pageable);

    /** SELECT ... FOR UPDATE: two concurrent cancels of one order run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id and o.shopId = :shopId")
    Optional<CustomerOrder> findForUpdate(@Param("id") long id, @Param("shopId") long shopId);
}
