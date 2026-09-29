package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ConflictException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Named CustomerOrder because ORDER is a reserved word in SQL. */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected CustomerOrder() {}

    public static CustomerOrder placed(long shopId, long userId) {
        CustomerOrder order = new CustomerOrder();
        order.shopId = shopId;
        order.createdBy = userId;
        order.status = OrderStatus.PLACED;
        order.total = BigDecimal.ZERO.setScale(2);
        order.createdAt = Instant.now();
        return order;
    }

    /** Only a PLACED order can be cancelled; this is the rule that stops stock being returned twice. */
    public void cancel(Instant when) {
        if (status != OrderStatus.PLACED) {
            throw new ConflictException("ORDER_NOT_CANCELLABLE", "Order " + id + " is already " + status);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = when;
    }

    public void setTotal(BigDecimal total) { this.total = total; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotal() { return total; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
