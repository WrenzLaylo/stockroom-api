package dev.wrenz.stockroom.product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(long id, String sku, String name, BigDecimal price, int stockQty, int lowStockThreshold,
                              boolean lowStock, boolean active, Instant createdAt, Instant updatedAt) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getPrice(), p.getStockQty(),
                p.getLowStockThreshold(), p.isLowStock(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
