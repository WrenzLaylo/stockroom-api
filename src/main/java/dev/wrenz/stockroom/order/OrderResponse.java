package dev.wrenz.stockroom.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(long id, OrderStatus status, BigDecimal total, long createdBy, Instant createdAt,
                            Instant cancelledAt, List<OrderLineResponse> items) {

    public static OrderResponse from(CustomerOrder order, List<OrderItem> items) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotal(), order.getCreatedBy(),
                order.getCreatedAt(), order.getCancelledAt(), items.stream().map(OrderLineResponse::from).toList());
    }
}
