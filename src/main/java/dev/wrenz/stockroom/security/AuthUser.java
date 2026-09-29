package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling. The ONLY place shopId comes from is the validated token (spec section 6):
 * never from the URL, body or query string.
 */
public record AuthUser(long userId, long shopId, Role role) {

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(
                Long.parseLong(jwt.getSubject()),
                ((Number) jwt.getClaim("shopId")).longValue(),
                Role.valueOf(jwt.getClaimAsString("role")));
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
