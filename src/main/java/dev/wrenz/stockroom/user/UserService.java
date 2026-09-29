package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.AuthUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public UserService(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public UserResponse createStaff(AuthUser caller, CreateUserRequest request) {
        ReservedEmails.requireNotReserved(request.email());
        AppUser staff = users.save(new AppUser(caller.shopId(), request.email(), encoder.encode(request.password()), Role.STAFF));
        return UserResponse.from(staff);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(AuthUser caller) {
        return users.findByShopIdOrderById(caller.shopId()).stream().map(UserResponse::from).toList();
    }
}
