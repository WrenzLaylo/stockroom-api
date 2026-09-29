package dev.wrenz.stockroom.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Tests migrate and TRUNCATE the database. This guard makes sure that database is the disposable
 * test one, before Flyway runs and before every wipe. A misconfigured TEST_DB_URL fails loudly
 * instead of destroying real data.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseGuard {

    public static final String EXPECTED_DATABASE = "stockroom_test";

    @Bean
    FlywayMigrationStrategy guardedMigration(DataSource dataSource) {
        return flyway -> {
            requireTestDatabase(new JdbcTemplate(dataSource));
            flyway.migrate();
        };
    }

    public static void requireTestDatabase(JdbcTemplate jdbc) {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (!EXPECTED_DATABASE.equals(database)) {
            throw new IllegalStateException("Refusing to run destructive tests against database '" + database
                    + "'. Tests only run against '" + EXPECTED_DATABASE + "'.");
        }
    }
}
