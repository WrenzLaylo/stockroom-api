package dev.wrenz.stockroom.schema;

import dev.wrenz.stockroom.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database backstop from spec section 3: even if Java code has a bug, PostgreSQL rejects
 * rows that cross shops, negative stock, and duplicate SKUs.
 */
class SchemaConstraintsTest extends IntegrationTest {

    long shopA, shopB, userA, userB, productA, orderB;

    @BeforeEach
    void twoShops() {
        shopA = id("INSERT INTO shop (name) VALUES ('A') RETURNING id");
        shopB = id("INSERT INTO shop (name) VALUES ('B') RETURNING id");
        userA = id("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'a@a.test', 'x', 'OWNER') RETURNING id", shopA);
        userB = id("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'b@b.test', 'x', 'OWNER') RETURNING id", shopB);
        productA = id("INSERT INTO product (shop_id, sku, name, price, stock_qty) VALUES (?, 'SKU-1', 'Thing', 10.00, 5) RETURNING id", shopA);
        orderB = id("INSERT INTO customer_order (shop_id, status, created_by) VALUES (?, 'PLACED', ?) RETURNING id", shopB, userB);
    }

    @Test
    void flywayCreatedTheSchema() {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void orderLineCannotPointAtAnotherShopsProduct() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_item (shop_id, order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, 1, 10.00)",
                shopB, orderB, productA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_order_item_product");
    }

    @Test
    void stockMovementCannotPointAtAnotherShopsProduct() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_movement (shop_id, product_id, change, reason, created_by) VALUES (?, ?, 1, 'RESTOCK', ?)",
                shopB, productA, userB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_movement_product");
    }

    @Test
    void orderCannotBeCreatedByAUserFromAnotherShop() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO customer_order (shop_id, status, created_by) VALUES (?, 'PLACED', ?)", shopA, userB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_order_created_by");
    }

    @Test
    void stockCannotGoNegative() {
        assertThatThrownBy(() -> jdbc.update("UPDATE product SET stock_qty = -1 WHERE id = ?", productA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_product_stock_nonneg");
    }

    @Test
    void skuIsUniquePerShopOnly() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product (shop_id, sku, name, price, stock_qty) VALUES (?, 'SKU-1', 'Dup', 1.00, 0)", shopA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_product_shop_sku");
        jdbc.update("INSERT INTO product (shop_id, sku, name, price, stock_qty) VALUES (?, 'SKU-1', 'Other shop', 1.00, 0)", shopB);
    }

    @Test
    void emailsMustBeStoredLowercase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'Mixed@Case.test', 'x', 'STAFF')", shopA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_app_user_email_lower");
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
