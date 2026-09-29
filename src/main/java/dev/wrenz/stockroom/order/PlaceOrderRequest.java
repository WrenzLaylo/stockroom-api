package dev.wrenz.stockroom.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** @NotNull on the element too: {"items":[null]} must be a 400, not a NullPointerException. */
public record PlaceOrderRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid OrderLineRequest> items) {}
