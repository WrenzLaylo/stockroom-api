package dev.wrenz.stockroom.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * trustForwardedFor must be true ONLY when every request arrives through a proxy that appends the
 * client address (Azure App Service's front end). Otherwise any caller could pick their own "IP".
 */
@ConfigurationProperties("stockroom.rate-limit")
public record RateLimitProperties(int maxAttemptsPerIp, int maxFailuresPerEmail, Duration window, long maxKeys,
                                  boolean trustForwardedFor) {}
