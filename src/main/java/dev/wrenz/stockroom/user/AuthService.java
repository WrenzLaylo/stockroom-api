package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.common.UnauthorizedException;
import dev.wrenz.stockroom.security.TokenService;
import dev.wrenz.stockroom.shop.Shop;
import dev.wrenz.stockroom.shop.ShopRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private final ShopRepository shops;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    /** Checked against when the email is unknown, so both failure paths take about the same time. */
    private final String dummyHash;

    public AuthService(ShopRepository shops, AppUserRepository users, PasswordEncoder encoder, TokenService tokens) {
        this.shops = shops;
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.dummyHash = encoder.encode("dummy-password-used-only-for-timing");
    }

    /** Shop and owner are created together or not at all. A duplicate email becomes 409 EMAIL_TAKEN. */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        ReservedEmails.requireNotReserved(request.email());
        Shop shop = shops.save(new Shop(request.shopName()));
        AppUser owner = users.save(new AppUser(shop.getId(), request.email(), encoder.encode(request.password()), Role.OWNER));
        return tokens.issue(owner);
    }

    /** Same 401 for an unknown email and a wrong password, so login can't be used to discover accounts. */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Optional<AppUser> user = users.findByEmail(request.email());
        boolean matches = encoder.matches(request.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return tokens.issue(user.get());
    }
}
