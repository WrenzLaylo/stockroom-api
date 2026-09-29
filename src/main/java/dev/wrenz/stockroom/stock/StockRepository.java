package dev.wrenz.stockroom.stock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY code that changes product.stock_qty after insert (spec section 5).
 *
 * Each change is one guarded SQL UPDATE: the check ("is there enough?") and the change happen in a
 * single atomic statement, and PostgreSQL's row lock makes concurrent orders wait their turn.
 * The matching stock_movement row is written in the same transaction, keeping the ledger in step.
 *
 * Before each UPDATE we flush pending JPA changes; after it we clear the persistence context, so no
 * stale Product entity survives in the transaction.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class StockRepository {

    /** Far below INTEGER's 2,147,483,647, leaving headroom for stock returned by cancellations. */
    public static final int MAX_STOCK = 1_000_000_000;

    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sale: succeeds only if the product is in this shop, active, and has enough stock. */
    public boolean deduct(long shopId, long productId, int quantity, long orderId, long userId) {
        return apply(shopId, productId, -quantity, MovementReason.SALE, orderId, null, userId, """
                UPDATE product SET stock_qty = stock_qty - ?, updated_at = now()
                WHERE id = ? AND shop_id = ? AND active = true AND stock_qty >= ?
                """, quantity, productId, shopId, quantity);
    }

    /** Restock: only active products in this shop, and never above MAX_STOCK. */
    public boolean restock(long shopId, long productId, int quantity, String note, long userId) {
        return apply(shopId, productId, quantity, MovementReason.RESTOCK, null, note, userId, """
                UPDATE product SET stock_qty = stock_qty + ?, updated_at = now()
                WHERE id = ? AND shop_id = ? AND active = true AND stock_qty <= ? - ?
                """, quantity, productId, shopId, MAX_STOCK, quantity);
    }

    /** Cancellation: reverses a sale, so archived products get their stock back too. */
    public boolean returnStock(long shopId, long productId, int quantity, long orderId, long userId) {
        return apply(shopId, productId, quantity, MovementReason.CANCEL, orderId, null, userId, """
                UPDATE product SET stock_qty = stock_qty + ?, updated_at = now()
                WHERE id = ? AND shop_id = ?
                """, quantity, productId, shopId);
    }

    /** The opening stock is set on insert; this writes the ledger row that explains it. */
    public void recordOpeningStock(long shopId, long productId, int quantity, long userId) {
        if (quantity > 0) {
            insertMovement(shopId, productId, quantity, MovementReason.OPENING_STOCK, null, null, userId);
        }
    }

    private boolean apply(long shopId, long productId, int change, MovementReason reason, Long orderId,
                          String note, long userId, String sql, Object... args) {
        entityManager.flush();
        int rows = jdbc.update(sql, args);
        if (rows == 1) {
            insertMovement(shopId, productId, change, reason, orderId, note, userId);
        }
        entityManager.clear();
        return rows == 1;
    }

    private void insertMovement(long shopId, long productId, int change, MovementReason reason, Long orderId,
                                String note, long userId) {
        jdbc.update("""
                INSERT INTO stock_movement (shop_id, product_id, change, reason, order_id, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, shopId, productId, change, reason.name(), orderId, note, userId);
    }
}
