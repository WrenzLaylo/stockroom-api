package dev.wrenz.stockroom.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** Deliberately has no stock field: stock only changes through restock and orders. */
public record ProductUpdateRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 200) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer lowStockThreshold) {

    public ProductUpdateRequest {
        sku = sku == null ? null : sku.trim();
        name = name == null ? null : name.trim();
    }
}
