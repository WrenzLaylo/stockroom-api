package dev.wrenz.stockroom.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndShopId(long id, long shopId);

    List<Product> findByShopIdAndIdIn(long shopId, Collection<Long> ids);

    /** pattern is a lowercase LIKE pattern with %, _ and \ already escaped (ProductService.likePattern). */
    @Query("""
            select p from Product p
            where p.shopId = :shopId
              and (:includeArchived = true or p.active = true)
              and (:lowStockOnly = false or p.stockQty <= p.lowStockThreshold)
              and (lower(p.name) like :pattern escape '\\' or lower(p.sku) like :pattern escape '\\')
            order by p.name, p.id
            """)
    Page<Product> search(@Param("shopId") long shopId,
                         @Param("includeArchived") boolean includeArchived,
                         @Param("lowStockOnly") boolean lowStockOnly,
                         @Param("pattern") String pattern,
                         Pageable pageable);
}
