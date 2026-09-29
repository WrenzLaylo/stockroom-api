package dev.wrenz.stockroom.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

    @Test
    void refusesMissingOrShortSecrets() {
        assertThatThrownBy(() -> JwtConfig.secretKey(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey("x".repeat(31))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsA32ByteSecret() {
        assertThat(JwtConfig.secretKey("x".repeat(32)).getAlgorithm()).isEqualTo("HmacSHA256");
    }
}
