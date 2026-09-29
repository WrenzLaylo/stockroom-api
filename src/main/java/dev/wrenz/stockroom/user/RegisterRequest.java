package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.Utf8MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record RegisterRequest(
        @NotBlank @Size(max = 120) String shopName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 8) @Utf8MaxBytes(72) String password) {

    /** Normalize before validation: trimmed shop name, trimmed lowercase email. */
    public RegisterRequest {
        shopName = shopName == null ? null : shopName.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
