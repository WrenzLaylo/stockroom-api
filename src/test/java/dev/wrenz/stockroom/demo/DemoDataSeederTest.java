package dev.wrenz.stockroom.demo;

import dev.wrenz.stockroom.support.TestDatabaseGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles({"test", "demo"})
@Import(TestDatabaseGuard.class)
class DemoDataSeederTest {

    @Autowired DemoDataSeeder seeder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        TestDatabaseGuard.requireTestDatabase(jdbc);
        jdbc.execute("TRUNCATE stock_movement, order_item, customer_order, product, app_user, shop RESTART IDENTITY CASCADE");
    }

    /** Leave nothing behind: a stray demo-domain user would make the next demo-profile startup fail closed. */
    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void seedsOnceAndNeverOverwritesWhatVisitorsChanged() throws Exception {
        seeder.run(null);
        assertThat(count("SELECT count(*) FROM product")).isEqualTo(15);
        assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(2);
        String ownerHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, DemoDataSeeder.OWNER_EMAIL);
        assertThat(encoder.matches(DemoDataSeeder.OWNER_PASSWORD, ownerHash)).isTrue();

        jdbc.update("UPDATE product SET name = 'Changed by a visitor' WHERE sku = 'RICE-KG'");
        seeder.run(null);

        assertThat(count("SELECT count(*) FROM product")).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE sku = 'RICE-KG'", String.class)).isEqualTo("Changed by a visitor");
    }

    @Test
    void openingStockIsInTheLedger() throws Exception {
        seeder.run(null);
        assertThat(jdbc.queryForList("""
                SELECT p.id FROM product p
                LEFT JOIN (SELECT product_id, SUM(change) AS total FROM stock_movement GROUP BY product_id) m ON m.product_id = p.id
                WHERE p.stock_qty <> COALESCE(m.total, 0)
                """, Long.class)).isEmpty();
    }

    @Test
    void resetDeletesOnlyTheFlaggedDemoShopEvenIfAnotherHasTheSameName() throws Exception {
        seeder.run(null);
        // A real customer who happens to use the same shop name must never be touched.
        long sameName = jdbc.queryForObject("INSERT INTO shop (name) VALUES (?) RETURNING id", Long.class, DemoDataSeeder.SHOP_NAME);
        jdbc.update("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'real@shop.test', 'x', 'OWNER')", sameName);
        String script = Files.readString(Path.of("ops/reset-demo.sql"));

        jdbc.execute(script);

        assertThat(count("SELECT count(*) FROM product")).isZero();
        assertThat(count("SELECT count(*) FROM shop")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_user WHERE email = 'real@shop.test'")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.execute(script)).hasMessageContaining("Demo shop not found");
    }

    @Test
    void seederFailsClosedIfADemoEmailIsHeldByANonDemoShop() {
        long impostor = jdbc.queryForObject("INSERT INTO shop (name) VALUES (?) RETURNING id", Long.class, DemoDataSeeder.SHOP_NAME);
        jdbc.update("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, ?, 'x', 'OWNER')", impostor, DemoDataSeeder.OWNER_EMAIL);

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DemoDataSeeder.OWNER_EMAIL);
        assertThat(count("SELECT count(*) FROM shop WHERE is_demo")).isZero();
        assertThat(count("SELECT count(*) FROM product")).isZero();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
