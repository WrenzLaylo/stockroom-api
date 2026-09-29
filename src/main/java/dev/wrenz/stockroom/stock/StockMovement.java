package dev.wrenz.stockroom.stock;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Read-only view of the append-only ledger. Rows are inserted by StockRepository with SQL. */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "change")
    private int change;

    @Enumerated(EnumType.STRING)
    private MovementReason reason;

    @Column(name = "order_id")
    private Long orderId;

    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    protected StockMovement() {}

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public Long getProductId() { return productId; }
    public int getChange() { return change; }
    public MovementReason getReason() { return reason; }
    public Long getOrderId() { return orderId; }
    public String getNote() { return note; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
