package dev.wrenz.stockroom.product;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @DynamicUpdate: an UPDATE contains only the columns that actually changed. An edit (sku, name, price,
 * threshold) therefore can't write back a stale `active`, and an archive can't write back a stale name
 * or price, even when both loaded the row before the other committed.
 */
@Entity
@Table(name = "product")
@DynamicUpdate
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /**
     * Written once on insert, never by JPA afterwards (updatable = false). All later changes go
     * through StockRepository's guarded UPDATEs, so a stale entity can't overwrite a sale (spec section 5).
     */
    @Column(name = "stock_qty", nullable = false, insertable = true, updatable = false)
    private int stockQty;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {}

    public Product(long shopId, String sku, String name, BigDecimal price, int lowStockThreshold, int openingStock) {
        this.shopId = shopId;
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.lowStockThreshold = lowStockThreshold;
        this.stockQty = openingStock;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String sku, String name, BigDecimal price, int lowStockThreshold) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.lowStockThreshold = lowStockThreshold;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public boolean isLowStock() { return stockQty <= lowStockThreshold; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public int getStockQty() { return stockQty; }
    public int getLowStockThreshold() { return lowStockThreshold; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
