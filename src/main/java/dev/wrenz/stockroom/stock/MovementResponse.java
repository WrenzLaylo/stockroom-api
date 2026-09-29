package dev.wrenz.stockroom.stock;

import java.time.Instant;

public record MovementResponse(long id, int change, MovementReason reason, Long orderId, String note,
                               long createdBy, Instant createdAt) {

    public static MovementResponse from(StockMovement m) {
        return new MovementResponse(m.getId(), m.getChange(), m.getReason(), m.getOrderId(), m.getNote(),
                m.getCreatedBy(), m.getCreatedAt());
    }
}
