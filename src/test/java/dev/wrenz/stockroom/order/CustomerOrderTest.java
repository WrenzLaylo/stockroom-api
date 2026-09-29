package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerOrderTest {

    @Test
    void newOrderIsPlacedWithZeroTotal() {
        CustomerOrder order = CustomerOrder.placed(1, 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getCancelledAt()).isNull();
    }

    @Test
    void placedOrderCanBeCancelledOnce() {
        CustomerOrder order = CustomerOrder.placed(1, 2);
        Instant when = Instant.parse("2026-09-28T10:00:00Z");
        order.cancel(when);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(when);

        assertThatThrownBy(() -> order.cancel(when))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ORDER_NOT_CANCELLABLE");
    }
}
