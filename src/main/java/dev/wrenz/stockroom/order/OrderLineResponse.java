package dev.wrenz.stockroom.order;

import java.math.BigDecimal;

public record OrderLineResponse(long productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    public static OrderLineResponse from(OrderItem item) {
        return new OrderLineResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice(),
                OrderRules.lineTotal(item.getUnitPrice(), item.getQuantity()));
    }
}
