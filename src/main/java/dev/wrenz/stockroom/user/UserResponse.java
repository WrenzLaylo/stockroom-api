package dev.wrenz.stockroom.user;

import java.time.Instant;

public record UserResponse(long id, String email, Role role, Instant createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
