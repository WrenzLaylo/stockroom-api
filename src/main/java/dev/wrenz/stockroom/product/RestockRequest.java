package dev.wrenz.stockroom.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RestockRequest(@NotNull @Min(1) @Max(100_000) Integer quantity, @Size(max = 200) String note) {}
