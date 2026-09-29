package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRulesTest {

    @Test
    void linesAreSortedByProductIdSoLocksAreTakenInOneOrder() {
        List<OrderLineRequest> sorted = OrderRules.sortedLines(List.of(
                new OrderLineRequest(9L, 1), new OrderLineRequest(2L, 3), new OrderLineRequest(5L, 1)));
        assertThat(sorted).extracting(OrderLineRequest::productId).containsExactly(2L, 5L, 9L);
    }

    @Test
    void duplicateProductsAreRejectedNotMerged() {
        assertThatThrownBy(() -> OrderRules.sortedLines(List.of(new OrderLineRequest(2L, 1), new OrderLineRequest(2L, 4))))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
    }

    @Test
    void totalsUseExactDecimalArithmetic() {
        assertThat(OrderRules.lineTotal(new BigDecimal("0.10"), 3)).isEqualByComparingTo("0.30");
        assertThat(OrderRules.lineTotal(new BigDecimal("12.50"), 2)).isEqualTo(new BigDecimal("25.00"));
        assertThat(OrderRules.orderTotal(List.of(new BigDecimal("25.00"), new BigDecimal("3.00")))).isEqualTo(new BigDecimal("28.00"));
        assertThat(OrderRules.orderTotal(List.of())).isEqualTo(new BigDecimal("0.00"));
    }
}
