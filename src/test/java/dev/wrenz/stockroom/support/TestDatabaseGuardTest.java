package dev.wrenz.stockroom.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDatabaseGuardTest {

    @Test
    void refusesAnyOtherDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("stockroom_dev");
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestDatabase(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stockroom_dev");
    }

    @Test
    void acceptsTheTestDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("stockroom_test");
        assertThatCode(() -> TestDatabaseGuard.requireTestDatabase(jdbc)).doesNotThrowAnyException();
    }
}
