package dev.wrenz.stockroom.concurrency;

import dev.wrenz.stockroom.common.ConflictException;
import dev.wrenz.stockroom.common.InsufficientStockException;
import dev.wrenz.stockroom.common.NotFoundException;
import dev.wrenz.stockroom.order.OrderLineRequest;
import dev.wrenz.stockroom.order.OrderService;
import dev.wrenz.stockroom.order.PlaceOrderRequest;
import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.product.ProductService;
import dev.wrenz.stockroom.product.ProductUpdateRequest;
import dev.wrenz.stockroom.product.RestockRequest;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import dev.wrenz.stockroom.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** The evidence for the core promise in spec section 1: real threads against real PostgreSQL. */
class ConcurrencyTest extends IntegrationTest {

    @Autowired OrderService orders;
    @Autowired ProductService productService;
    @Autowired ProductRepository products;
    @Autowired TransactionTemplate tx;

    TestApi.Session session;
    AuthUser owner;
    ExecutorService pool;

    @BeforeEach
    void shop() throws Exception {
        session = api.registerShop("Shop", "owner@shop.test");
        owner = new AuthUser(session.userId(), session.shopId(), Role.OWNER);
        pool = Executors.newFixedThreadPool(20);
    }

    /** Make sure no worker transaction outlives this test and overlaps the next test's TRUNCATE. */
    @AfterEach
    void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(45, TimeUnit.SECONDS))
                .as("worker threads still running; the next test's cleanup would race them").isTrue();
    }

    @Test
    void twentyBuyersForFiveUnitsSellExactlyFive() throws Exception {
        long pid = api.createProduct(session, "HOT-ITEM", "10.00", 5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    orders.place(owner, order(line(pid, 1)));
                    return "SOLD";
                } catch (InsufficientStockException e) {
                    return "REJECTED";
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));

        assertThat(outcomes).filteredOn("SOLD"::equals).hasSize(5);
        assertThat(outcomes).filteredOn("REJECTED"::equals).hasSize(15);
        assertThat(stockOf(pid)).isZero();
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM customer_order")).isEqualTo(5);
        assertLedgerBalanced();
    }

    @Test
    void oppositeLineOrderAndCancellationsDoNotDeadlock() throws Exception {
        long a = api.createProduct(session, "A", "1.00", 10_000);
        long b = api.createProduct(session, "B", "1.00", 10_000);
        // Orders to cancel exist BEFORE the race starts, so the canceller always has real work to overlap.
        List<Long> toCancel = new ArrayList<>();
        for (int i = 0; i < 50; i++) toCancel.add(orders.place(owner, order(line(a, 1), line(b, 1))).id());

        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> ab = () -> { start.await(); for (int i = 0; i < 100; i++) orders.place(owner, order(line(a, 1), line(b, 1))); return null; };
        Callable<Void> ba = () -> { start.await(); for (int i = 0; i < 100; i++) orders.place(owner, order(line(b, 1), line(a, 1))); return null; };
        Callable<Void> canceller = () -> { start.await(); for (long id : toCancel) orders.cancel(owner, id); return null; };
        List<Future<Void>> futures = List.of(pool.submit(ab), pool.submit(ba), pool.submit(canceller));
        start.countDown();
        for (Future<Void> f : futures) {
            f.get(120, TimeUnit.SECONDS);   // rethrows any deadlock (surfacing as a ConcurrencyFailureException)
        }

        assertThat(count("SELECT count(*) FROM customer_order WHERE status = 'CANCELLED'")).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM customer_order WHERE status = 'PLACED'")).isEqualTo(200);
        assertThat(stockOf(a)).isEqualTo(10_000 - 200);
        assertThat(stockOf(b)).isEqualTo(10_000 - 200);
        assertLedgerBalanced();
    }

    @Test
    void twoSimultaneousCancelsReturnStockExactlyOnce() throws Exception {
        long pid = api.createProduct(session, "CANCEL-ME", "1.00", 10);
        for (int round = 0; round < 10; round++) {
            long orderId = orders.place(owner, order(line(pid, 3))).id();
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> cancel = () -> {
                start.await();
                try {
                    orders.cancel(owner, orderId);
                    return "CANCELLED";
                } catch (ConflictException alreadyCancelled) {
                    return "CONFLICT";
                }
            };
            Future<String> first = pool.submit(cancel);
            Future<String> second = pool.submit(cancel);
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CANCELLED", "CONFLICT");
            assertThat(stockOf(pid)).isEqualTo(10);
        }
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'CANCEL'")).isEqualTo(10);
        assertLedgerBalanced();
    }

    @Test
    void archiveRacingAnOrderHasOnlyTheTwoDefinedOutcomes() throws Exception {
        for (int i = 0; i < 20; i++) {
            long pid = api.createProduct(session, "RACE-" + i, "5.00", 5);
            CountDownLatch start = new CountDownLatch(1);
            Future<String> order = pool.submit(() -> {
                start.await();
                try {
                    orders.place(owner, order(line(pid, 1)));
                    return "ORDERED";
                } catch (NotFoundException e) {
                    return "REJECTED";
                }
            });
            Future<?> archive = pool.submit(() -> { start.await(); productService.archive(owner, pid); return null; });
            start.countDown();
            String outcome = order.get(30, TimeUnit.SECONDS);
            archive.get(30, TimeUnit.SECONDS);

            assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id = ?", Boolean.class, pid)).isFalse();
            assertThat(stockOf(pid)).isEqualTo(outcome.equals("ORDERED") ? 4 : 5);
        }
        assertLedgerBalanced();
    }

    /** Spec section 5: a PUT that loaded the product before an order committed must not erase the sale. */
    @Test
    void productEditRacingAnOrderKeepsTheSale() throws Exception {
        long pid = api.createProduct(session, "EDITED", "10.00", 5);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch ordered = new CountDownLatch(1);
        Future<?> edit = pool.submit(() -> tx.executeWithoutResult(status -> {
            Product p = products.findByIdAndShopId(pid, session.shopId()).orElseThrow();
            loaded.countDown();
            await(ordered);
            p.update(p.getSku(), "Renamed while an order ran", p.getPrice(), p.getLowStockThreshold());
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        orders.place(owner, order(line(pid, 2)));
        ordered.countDown();
        edit.get(30, TimeUnit.SECONDS);

        assertThat(stockOf(pid)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid)).isEqualTo("Renamed while an order ran");
        assertLedgerBalanced();
    }

    /** Spec section 5 plus @DynamicUpdate: an edit that loaded the row before an archive must not un-archive it. */
    @Test
    void editRacingAnArchiveKeepsTheProductArchived() throws Exception {
        long pid = api.createProduct(session, "EDIT-VS-ARCHIVE", "10.00", 5);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch archived = new CountDownLatch(1);
        Future<?> edit = pool.submit(() -> tx.executeWithoutResult(status -> {
            Product p = products.findByIdAndShopId(pid, session.shopId()).orElseThrow();   // sees active = true
            loaded.countDown();
            await(archived);
            p.update(p.getSku(), "Edited after archive", p.getPrice(), p.getLowStockThreshold());
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        productService.archive(owner, pid);
        archived.countDown();
        edit.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id = ?", Boolean.class, pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid)).isEqualTo("Edited after archive");
    }

    @Test
    void archiveRacingAnEditKeepsTheNewName() throws Exception {
        long pid = api.createProduct(session, "ARCHIVE-VS-EDIT", "10.00", 5);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch edited = new CountDownLatch(1);
        Future<?> archive = pool.submit(() -> tx.executeWithoutResult(status -> {
            Product p = products.findByIdAndShopId(pid, session.shopId()).orElseThrow();   // sees the old name
            loaded.countDown();
            await(edited);
            p.archive();
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        productService.update(owner, pid, new ProductUpdateRequest("ARCHIVE-VS-EDIT", "New name", new BigDecimal("12.00"), 5));
        edited.countDown();
        archive.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id = ?", Boolean.class, pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid)).isEqualTo("New name");
        assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", BigDecimal.class, pid)).isEqualByComparingTo("12.00");
    }

    @Test
    void ledgerMatchesStockAfterMixedOperations() throws Exception {
        long pid = api.createProduct(session, "MIXED", "2.00", 10);
        productService.restock(owner, pid, new RestockRequest(5, "delivery"));
        long o1 = orders.place(owner, order(line(pid, 3))).id();
        orders.place(owner, order(line(pid, 4)));
        orders.cancel(owner, o1);
        assertThat(stockOf(pid)).isEqualTo(11);
        assertLedgerBalanced();
    }

    private void assertLedgerBalanced() {
        List<Long> mismatched = jdbc.queryForList("""
                SELECT p.id FROM product p
                LEFT JOIN (SELECT product_id, SUM(change) AS total FROM stock_movement GROUP BY product_id) m
                       ON m.product_id = p.id
                WHERE p.stock_qty <> COALESCE(m.total, 0)
                """, Long.class);
        assertThat(mismatched).as("products whose stock differs from their ledger").isEmpty();
    }

    private static PlaceOrderRequest order(OrderLineRequest... lines) {
        return new PlaceOrderRequest(List.of(lines));
    }

    private static OrderLineRequest line(long productId, int quantity) {
        return new OrderLineRequest(productId, quantity);
    }

    private int stockOf(long pid) {
        return jdbc.queryForObject("SELECT stock_qty FROM product WHERE id = ?", Integer.class, pid);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
