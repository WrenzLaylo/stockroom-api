package dev.wrenz.stockroom.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8MaxBytesValidatorTest {

    private final Utf8MaxBytesValidator validator = new Utf8MaxBytesValidator(72);

    @Test
    void countsBytesNotCharacters() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("a".repeat(72), null)).isTrue();
        assertThat(validator.isValid("a".repeat(73), null)).isFalse();
        // "é" is 2 bytes in UTF-8: 40 characters = 80 bytes
        assertThat(validator.isValid("é".repeat(40), null)).isFalse();
        assertThat(validator.isValid("é".repeat(36), null)).isTrue();
    }
}
