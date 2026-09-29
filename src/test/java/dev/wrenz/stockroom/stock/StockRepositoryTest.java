package dev.wrenz.stockroom.stock;

import dev.wrenz.stockroom.order.CustomerOrder;
import dev.wrenz.stockroom.order.CustomerOrderRepository;
import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.shop.Shop;
import dev.wrenz.stockroom.shop.ShopRepository;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.AppUserRepository;
import dev.wrenz.stockroom.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockRepositoryTest extends IntegrationTest {

    @Autowired ShopRepository shops;
    @Autowired AppUserRepository users;
    @Autowired ProductRepository products;
    @Autowired CustomerOrderRepository orders;
    @Autowired StockRepository stock;
    @Autowired TransactionTemplate tx;

    long shopId, otherShopId, userId, productId, orderId;

    @BeforeEach
    void data() {
        shopId = shops.save(new Shop("Shop")).getId();
        otherShopId = shops.save(new Shop("Other")).getId();
        userId = users.save(new AppUser(shopId, "o@shop.test", "hash", Role.OWNER)).getId();
        productId = products.save(new Product(shopId, "SKU-1", "Rice", new BigDecimal("52.00"), 5, 5)).getId();
        orderId = orders.save(CustomerOrder.placed(shopId, userId)).getId();
    }

    @Test
    void deductRemovesStockAndRecordsASale() {
        boolean ok = inTx(() -> stock.deduct(shopId, productId, 2, orderId, userId));
        assertThat(ok).isTrue();
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT change FROM stock_movement WHERE reason = 'SALE' AND order_id = ?", Integer.class, orderId)).isEqualTo(-2);
    }

    @Test
    void deductRefusesWhenStockIsShortAndChangesNothing() {
        boolean ok = inTx(() -> stock.deduct(shopId, productId, 6, orderId, userId));
        assertThat(ok).isFalse();
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(movementCount()).isZero();
    }

    @Test
    void deductRefusesArchivedProductsAndOtherShops() {
        assertThat(inTx(() -> stock.deduct(otherShopId, productId, 1, orderId, userId))).isFalse();
        jdbc.update("UPDATE product SET active = false WHERE id = ?", productId);
        assertThat(inTx(() -> stock.deduct(shopId, productId, 1, orderId, userId))).isFalse();
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    @Test
    void restockAddsStockWithNoteButNotForArchivedProducts() {
        assertThat(inTx(() -> stock.restock(shopId, productId, 10, "Supplier delivery", userId))).isTrue();
        assertThat(stockOf(productId)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT note FROM stock_movement WHERE reason = 'RESTOCK'", String.class)).isEqualTo("Supplier delivery");

        jdbc.update("UPDATE product SET active = false WHERE id = ?", productId);
        assertThat(inTx(() -> stock.restock(shopId, productId, 10, null, userId))).isFalse();
        assertThat(stockOf(productId)).isEqualTo(15);
    }

    @Test
    void restockRefusesToExceedTheStockLimit() {
        jdbc.update("UPDATE product SET stock_qty = ? WHERE id = ?", StockRepository.MAX_STOCK - 10, productId);
        assertThat(inTx(() -> stock.restock(shopId, productId, 11, null, userId))).isFalse();
        assertThat(inTx(() -> stock.restock(shopId, productId, 10, null, userId))).isTrue();
        assertThat(stockOf(productId)).isEqualTo(StockRepository.MAX_STOCK);
    }

    @Test
    void returnStockWorksEvenForArchivedProducts() {
        jdbc.update("UPDATE product SET active = false WHERE id = ?", productId);
        assertThat(inTx(() -> stock.returnStock(shopId, productId, 2, orderId, userId))).isTrue();
        assertThat(stockOf(productId)).isEqualTo(7);
    }

    @Test
    void recordOpeningStockOnlyWritesTheMovement() {
        tx.executeWithoutResult(s -> stock.recordOpeningStock(shopId, productId, 5, userId));
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT change FROM stock_movement WHERE reason = 'OPENING_STOCK'", Integer.class)).isEqualTo(5);
        tx.executeWithoutResult(s -> stock.recordOpeningStock(shopId, productId, 0, userId));
        assertThat(movementCount()).isEqualTo(1);
    }

    /** Spec section 5, rule 1 and 2: a loaded or dirty Product entity must never overwrite stock. */
    @Test
    void staleProductEntityCannotOverwriteADeduction() {
        tx.executeWithoutResult(s -> {
            Product before = products.findByIdAndShopId(productId, shopId).orElseThrow();
            before.update(before.getSku(), "Renamed", before.getPrice(), before.getLowStockThreshold());
            assertThat(stock.deduct(shopId, productId, 2, orderId, userId)).isTrue();
            Product after = products.findByIdAndShopId(productId, shopId).orElseThrow();
            assertThat(after.getStockQty()).isEqualTo(3);
            after.update(after.getSku(), "Renamed twice", after.getPrice(), after.getLowStockThreshold());
        });
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, productId)).isEqualTo("Renamed twice");
    }

    @Test
    void stockChangesRequireATransaction() {
        assertThatThrownBy(() -> stock.deduct(shopId, productId, 1, orderId, userId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** Runs a stock change inside a transaction (StockRepository requires one) and returns its result. */
    private boolean inTx(BooleanSupplier change) {
        return Boolean.TRUE.equals(tx.execute(s -> change.getAsBoolean()));
    }

    private int stockOf(long id) {
        return jdbc.queryForObject("SELECT stock_qty FROM product WHERE id = ?", Integer.class, id);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class);
    }
}
