package dev.wrenz.stockroom.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("stockroom.jwt")
public record JwtProperties(String secret, Duration ttl) {}
