package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure order rules, no database: easy to unit test and to explain. */
public final class OrderRules {

    private OrderRules() {}

    /**
     * Rejects duplicate products (400 rather than silently merging), then sorts by product ID.
     * Every order locks product rows in the same ascending order, so two orders can't deadlock
     * waiting on each other (spec section 5).
     */
    public static List<OrderLineRequest> sortedLines(List<OrderLineRequest> lines) {
        Set<Long> seen = new HashSet<>();
        for (OrderLineRequest line : lines) {
            if (!seen.add(line.productId())) {
                throw new ValidationException("items", "product " + line.productId() + " appears more than once");
            }
        }
        return lines.stream().sorted(Comparator.comparing(OrderLineRequest::productId)).toList();
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal orderTotal(List<BigDecimal> lineTotals) {
        return lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
