package dev.wrenz.stockroom.support;

import dev.wrenz.stockroom.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full application against the real PostgreSQL test database, wiped before every test. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected LoginRateLimiter rateLimiter;

    protected TestApi api;

    @BeforeEach
    void resetDatabase() {
        TestDatabaseGuard.requireTestDatabase(jdbc);
        jdbc.execute("TRUNCATE stock_movement, order_item, customer_order, product, app_user, shop RESTART IDENTITY CASCADE");
        rateLimiter.clear();
        api = new TestApi(mvc);
    }
}
