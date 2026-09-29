package dev.wrenz.stockroom.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 200) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal price,
        @Min(0) Integer lowStockThreshold,
        @Min(0) @Max(1_000_000) Integer initialStock) {

    public ProductRequest {
        sku = sku == null ? null : sku.trim();
        name = name == null ? null : name.trim();
    }

    public int thresholdOrDefault() { return lowStockThreshold == null ? 5 : lowStockThreshold; }
    public int initialStockOrZero() { return initialStock == null ? 0 : initialStock; }
}
