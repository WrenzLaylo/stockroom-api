package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class InsufficientStockException extends ApiException {
    public InsufficientStockException(long productId, int requested, int available) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Not enough stock for product " + productId + ": requested " + requested + ", available " + available,
                Map.of("productId", productId, "requested", requested, "available", available));
    }
}
