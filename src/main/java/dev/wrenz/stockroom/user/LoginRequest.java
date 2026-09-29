package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.Utf8MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;

public record LoginRequest(@NotBlank String email, @NotNull @Utf8MaxBytes(72) String password) {

    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
