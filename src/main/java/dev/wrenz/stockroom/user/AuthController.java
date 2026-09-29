package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.ClientIpResolver;
import dev.wrenz.stockroom.security.RateLimitProperties;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Empty @SecurityRequirements: Swagger UI must not attach the (possibly expired) token to these calls. */
@RestController
@RequestMapping("/api/auth")
@SecurityRequirements
public class AuthController {

    private final AuthService auth;
    private final boolean trustForwardedFor;

    public AuthController(AuthService auth, RateLimitProperties limits) {
        this.auth = auth;
        this.trustForwardedFor = limits.trustForwardedFor();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return auth.register(request, ClientIpResolver.resolve(http, trustForwardedFor));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return auth.login(request, ClientIpResolver.resolve(http, trustForwardedFor));
    }
}
