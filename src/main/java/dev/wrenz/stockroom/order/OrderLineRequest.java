package dev.wrenz.stockroom.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderLineRequest(@NotNull Long productId, @NotNull @Min(1) @Max(1000) Integer quantity) {}
