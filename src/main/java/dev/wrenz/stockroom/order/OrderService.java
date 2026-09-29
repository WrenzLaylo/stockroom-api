package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.InsufficientStockException;
import dev.wrenz.stockroom.common.NotFoundException;
import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.stock.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final CustomerOrderRepository orders;
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final StockRepository stock;

    public OrderService(CustomerOrderRepository orders, OrderItemRepository orderItems, ProductRepository products,
                        StockRepository stock) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.products = products;
        this.stock = stock;
    }

    /**
     * One transaction (spec section 5): insert the order, deduct each line with a guarded UPDATE in
     * product ID order, then price the lines from the now-locked rows. Any failure rolls back everything.
     */
    @Transactional
    public OrderResponse place(AuthUser caller, PlaceOrderRequest request) {
        List<OrderLineRequest> lines = OrderRules.sortedLines(request.items());
        long orderId = orders.saveAndFlush(CustomerOrder.placed(caller.shopId(), caller.userId())).getId();

        for (OrderLineRequest line : lines) {
            if (!stock.deduct(caller.shopId(), line.productId(), line.quantity(), orderId, caller.userId())) {
                throw stockFailure(caller, line);
            }
        }

        Map<Long, Product> byId = products.findByShopIdAndIdIn(caller.shopId(),
                        lines.stream().map(OrderLineRequest::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<OrderItem> items = new ArrayList<>();
        List<BigDecimal> lineTotals = new ArrayList<>();
        for (OrderLineRequest line : lines) {
            BigDecimal unitPrice = byId.get(line.productId()).getPrice();
            items.add(new OrderItem(caller.shopId(), orderId, line.productId(), line.quantity(), unitPrice));
            lineTotals.add(OrderRules.lineTotal(unitPrice, line.quantity()));
        }
        orderItems.saveAll(items);

        CustomerOrder order = orders.findByIdAndShopId(orderId, caller.shopId()).orElseThrow();
        order.setTotal(OrderRules.orderTotal(lineTotals));
        return OrderResponse.from(order, items);
    }

    /** The deduction matched no row: find out why, so the client gets 404 or a precise 409. */
    private RuntimeException stockFailure(AuthUser caller, OrderLineRequest line) {
        return products.findByIdAndShopId(line.productId(), caller.shopId())
                .filter(Product::isActive)
                .<RuntimeException>map(p -> new InsufficientStockException(p.getId(), line.quantity(), p.getStockQty()))
                .orElseGet(() -> new NotFoundException("Product", line.productId()));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(AuthUser caller, long id) {
        CustomerOrder order = orders.findByIdAndShopId(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Order", id));
        return OrderResponse.from(order, orderItems.findByShopIdAndOrderIdOrderByProductId(caller.shopId(), id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> list(AuthUser caller, int page, int size) {
        return PageResponse.of(orders.findByShopIdOrderByCreatedAtDescIdDesc(caller.shopId(), PageResponse.pageable(page, size))
                .map(OrderSummaryResponse::from));
    }

    /**
     * Lock the order row first (a second cancel waits, then sees CANCELLED and gets 409), mark it
     * cancelled, then return stock in ascending product ID order: the same lock order as place().
     */
    @Transactional
    public OrderResponse cancel(AuthUser caller, long id) {
        CustomerOrder order = orders.findForUpdate(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Order", id));
        order.cancel(Instant.now());
        orders.saveAndFlush(order);

        List<OrderItem> items = orderItems.findByShopIdAndOrderIdOrderByProductId(caller.shopId(), id);
        for (OrderItem item : items) {
            if (!stock.returnStock(caller.shopId(), item.getProductId(), item.getQuantity(), id, caller.userId())) {
                throw new IllegalStateException("Order " + id + " references missing product " + item.getProductId());
            }
        }
        return get(caller, id);
    }
}
