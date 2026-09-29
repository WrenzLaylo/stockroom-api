package dev.wrenz.stockroom.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(long id, OrderStatus status, BigDecimal total, long createdBy, Instant createdAt,
                                   Instant cancelledAt) {

    public static OrderSummaryResponse from(CustomerOrder o) {
        return new OrderSummaryResponse(o.getId(), o.getStatus(), o.getTotal(), o.getCreatedBy(), o.getCreatedAt(),
                o.getCancelledAt());
    }
}
