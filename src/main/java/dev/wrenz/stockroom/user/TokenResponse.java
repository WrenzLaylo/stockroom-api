package dev.wrenz.stockroom.user;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, long userId, long shopId, Role role) {}
