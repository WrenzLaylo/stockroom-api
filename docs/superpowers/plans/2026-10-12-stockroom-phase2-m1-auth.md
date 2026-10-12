# Stockroom Phase 2, Milestone M1: Authentication Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the API check every caller against the database on every request, add "stay logged in" refresh tokens with rotation and reuse detection, and let owners disable, re-enable, promote and demote staff.

**Architecture:** A new `CurrentUserFilter` runs right after Spring's bearer-token filter, loads the user row and replaces the token-only authentication with one whose roles come from the database. Refresh tokens live in a new `session` package: plain SQL in `RefreshTokenRepository` (conditional updates decide races, the same idiom as `StockRepository`), the rules in `RefreshTokenService`, and an `HttpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie. User changes go through one locked, rule-checked `UserService.update`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7 (OAuth2 resource server), JPA + `JdbcTemplate`, Flyway, PostgreSQL 16, JUnit 5, MockMvc, Mockito (`@MockitoBean`).

**Spec:** `docs/superpowers/specs/2026-10-12-stockroom-phase2-design.md`, section 4 (all of it), sections 7, 8.1, 8.2. Read section 4 before starting.

## Global Constraints

- Access tokens last **15 minutes** (`stockroom.jwt.ttl: 15m`). Refresh tokens last **14 days**; a session (family) lasts **30 days** at most. At most **50** live sessions per user.
- Refresh cookie: name `stockroom_refresh`, `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`. Only a **SHA-256 hash** (base64url, 43 characters) is stored.
- Error shapes stay RFC 9457 `ProblemDetail` with a `code` (P1 §7). New codes: `REFRESH_INVALID` (401), `REFRESH_REUSED` (401), `CANNOT_CHANGE_SELF` (409), `LAST_OWNER` (409), `DEMO_ACCOUNT_PROTECTED` (409).
- A database failure during the per-request user lookup is **500 `INTERNAL_ERROR`, never 401**.
- Refresh and logout reject `Sec-Fetch-Site` values other than `same-origin`; a missing header is allowed. Refresh has its own per-IP limit of **60 per 15 minutes**.
- No cache for the per-request lookup. No hard delete of users. No `DELETE /api/users/{id}`.
- Git: branch `m1/auth-hardening` from `main`, one PR, Wrenz merges. **No `Co-Authored-By` or "Generated with Claude Code" lines.**
- Never kill processes by name. Write files with the Write tool (heredocs with apostrophes break in this harness).
- Java builds: `. .superpowers/env.sh` first; local PostgreSQL 16 running with the `stockroom_test` database.
- Test counts below are relative ("previous + N"), because M0 may or may not be merged when M1 starts.

## Review Focus

1. **Swagger users after 15 minutes:** Swagger keeps sending an expired token; login must still work (the existing `anExpiredTokenDoesNotBlockLoginOrRegister` test must keep passing in Task 2) and a refresh with no cookie must be a clean `REFRESH_INVALID`, not a 500. Pinned by `deadTokensAreInvalid` (Task 4).
2. **A visitor who logs in many times as the shared demo owner:** rows must stay bounded. Pinned by `loginTidiesDeadTokensAndKeepsAtMostFiftySessions` (Task 5).
3. **A malformed token claim** (non-numeric `sub`, missing `shopId`) signed with the real key: must be a 401, never a 500. Pinned by `aTokenWithoutAShopClaimIsRefused` (Task 2).
4. **An owner who refreshes after being demoted:** the new access token must carry the current role. Pinned by `refreshIssuesTheCurrentRole` (Task 4).
5. **The caller never appears as an API parameter in Swagger** after the principal type changes. Pinned by `theCallerIsNeverADocumentedParameter` (Task 2).

---

### Task 1: Schema V2 and user status

**Files:**
- Create: `src/main/resources/db/migration/V2__auth_sessions.sql`
- Create: `src/main/java/dev/wrenz/stockroom/user/UserStatus.java`
- Modify: `src/main/java/dev/wrenz/stockroom/user/AppUser.java`
- Modify: `ops/reset-demo.sql`
- Modify: `src/test/java/dev/wrenz/stockroom/support/IntegrationTest.java`
- Modify: `src/test/java/dev/wrenz/stockroom/demo/DemoDataSeederTest.java`
- Modify: `src/test/java/dev/wrenz/stockroom/schema/SchemaConstraintsTest.java`

**Interfaces:**
- Produces: table `refresh_token` (columns per spec 4.2); `app_user.status`; `enum UserStatus { ACTIVE, DISABLED }`; `AppUser.getStatus()`, `AppUser.isActive()`, `AppUser.changeRole(Role)`, `AppUser.changeStatus(UserStatus)`.

- [ ] **Step 1: Create the branch**

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
git checkout main && git pull
git checkout -b m1/auth-hardening
. .superpowers/env.sh
```

- [ ] **Step 2: Write the failing schema tests**

In `src/test/java/dev/wrenz/stockroom/schema/SchemaConstraintsTest.java`:

1. In `flywayCreatedTheSchema`, change `isEqualTo(1)` to `isEqualTo(2)`.
2. Add these two tests at the end of the class:

```java
    @Test
    void newUsersAreActiveAndStatusIsChecked() {
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, userA)).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET status = 'BANNED' WHERE id = ?", userA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_app_user_status");
    }

    @Test
    void refreshTokenCannotPointAtAnotherShopsUser() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO refresh_token (shop_id, user_id, family_id, token_hash, expires_at, family_expires_at)
                VALUES (?, ?, gen_random_uuid(), ?, now() + interval '1 day', now() + interval '1 day')
                """, shopB, userA, "h".repeat(43)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_refresh_token_user");
    }
```

In `src/test/java/dev/wrenz/stockroom/demo/DemoDataSeederTest.java`, in `resetDeletesOnlyTheFlaggedDemoShopEvenIfAnotherHasTheSameName`, insert these lines directly **before** `String script = Files.readString(Path.of("ops/reset-demo.sql"));`:

```java
        // A logged-in demo visitor leaves a refresh token behind; the reset must remove it first.
        jdbc.update("""
                INSERT INTO refresh_token (shop_id, user_id, family_id, token_hash, expires_at, family_expires_at)
                SELECT shop_id, id, gen_random_uuid(), ?, now() + interval '1 day', now() + interval '1 day'
                FROM app_user WHERE email = ?
                """, "r".repeat(43), DemoDataSeeder.OWNER_EMAIL);
```

and add this line after `assertThat(count("SELECT count(*) FROM product")).isZero();`:

```java
        assertThat(count("SELECT count(*) FROM refresh_token")).isZero();
```

- [ ] **Step 3: Run them and watch them fail**

```bash
./mvnw -B test -Dtest='SchemaConstraintsTest,DemoDataSeederTest'
```

Expected: failures. `flywayCreatedTheSchema` sees 1, and the new tests fail with `column "status" does not exist` / `relation "refresh_token" does not exist`.

- [ ] **Step 4: Add the migration**

Create `src/main/resources/db/migration/V2__auth_sessions.sql`:

```sql
-- Phase 2, spec section 4: user status for the per-request check, and refresh tokens for "stay logged in".
ALTER TABLE app_user
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'));

-- Only a SHA-256 hash of each token is stored. A family is one login session; every rotation adds a row.
-- family_expires_at is copied from the session's first token, so the 30-day limit needs no extra lookup.
CREATE TABLE refresh_token (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id            BIGINT      NOT NULL,
    user_id            BIGINT      NOT NULL,
    family_id          UUID        NOT NULL,
    token_hash         CHAR(43)    NOT NULL CONSTRAINT uq_refresh_token_hash UNIQUE,
    issued_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    family_expires_at  TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id, shop_id) REFERENCES app_user (id, shop_id)
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
```

- [ ] **Step 5: Map the status**

Create `src/main/java/dev/wrenz/stockroom/user/UserStatus.java`:

```java
package dev.wrenz.stockroom.user;

/** DISABLED users are refused on their next request (spec section 4.4). Nobody is ever hard-deleted. */
public enum UserStatus { ACTIVE, DISABLED }
```

In `src/main/java/dev/wrenz/stockroom/user/AppUser.java`:

1. After the `role` field, add:

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;
```

2. In the public constructor, after `this.role = role;`, add `this.status = UserStatus.ACTIVE;`.
3. After `public Role getRole() { return role; }`, add:

```java
    public UserStatus getStatus() { return status; }
    public boolean isActive() { return status == UserStatus.ACTIVE; }

    /** Only UserService.update calls these, after its rules have passed (spec section 4.5). */
    public void changeRole(Role role) { this.role = role; }
    public void changeStatus(UserStatus status) { this.status = status; }
```

- [ ] **Step 6: Keep the wipes and the reset script complete**

In `src/test/java/dev/wrenz/stockroom/support/IntegrationTest.java` and in `DemoDataSeederTest.clean()`, change the TRUNCATE statement to:

```java
        jdbc.execute("TRUNCATE refresh_token, stock_movement, order_item, customer_order, product, app_user, shop RESTART IDENTITY CASCADE");
```

In `ops/reset-demo.sql`, add this line directly before `DELETE FROM app_user WHERE shop_id = demo_shop_id;`:

```sql
    DELETE FROM refresh_token WHERE shop_id = demo_shop_id;
```

- [ ] **Step 7: Run the tests and the full suite**

```bash
./mvnw -B test -Dtest='SchemaConstraintsTest,DemoDataSeederTest'
./mvnw -B verify
```

Expected: the two classes pass, then `BUILD SUCCESS` with the previous test count + 2 and 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V2__auth_sessions.sql src/main/java/dev/wrenz/stockroom/user/UserStatus.java \
  src/main/java/dev/wrenz/stockroom/user/AppUser.java ops/reset-demo.sql \
  src/test/java/dev/wrenz/stockroom/support/IntegrationTest.java \
  src/test/java/dev/wrenz/stockroom/demo/DemoDataSeederTest.java \
  src/test/java/dev/wrenz/stockroom/schema/SchemaConstraintsTest.java
git commit -m "feat: V2 schema: user status and refresh_token table; demo reset removes refresh tokens"
```

---

### Task 2: The database decides on every request

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/security/CurrentUserLookup.java`
- Create: `src/main/java/dev/wrenz/stockroom/security/CurrentUserAuthentication.java`
- Create: `src/main/java/dev/wrenz/stockroom/security/CurrentUserFilter.java`
- Delete: `src/main/java/dev/wrenz/stockroom/security/UserIdRequestAttributeFilter.java`
- Modify: `src/main/java/dev/wrenz/stockroom/security/AuthUser.java`
- Modify: `src/main/java/dev/wrenz/stockroom/security/SecurityConfig.java`
- Modify: `src/main/java/dev/wrenz/stockroom/common/GlobalExceptionHandler.java` (`handleAuthentication`)
- Modify: `src/main/java/dev/wrenz/stockroom/order/OrderController.java`, `product/ProductController.java`, `user/UserController.java`
- Modify: `src/main/resources/application.yml` (`stockroom.jwt.ttl`)
- Create: `src/test/java/dev/wrenz/stockroom/security/LiveUserCheckTest.java`
- Create: `src/test/java/dev/wrenz/stockroom/security/UserLookupFailureTest.java`
- Modify: `src/test/java/dev/wrenz/stockroom/security/TokenCasesTest.java`, `config/OpenApiTest.java`, `user/AuthIntegrationTest.java`

**Interfaces:**
- Consumes: `AppUser.isActive()` (Task 1).
- Produces: `record AuthUser(long userId, long shopId, Role role)` with `isOwner()` and **no** `from(Jwt)`; controllers receive `@AuthenticationPrincipal AuthUser user`; `CurrentUserLookup.load(Jwt): AuthUser` (throws `InvalidBearerTokenException`); `CurrentUserAuthentication(AuthUser, Jwt)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/dev/wrenz/stockroom/security/LiveUserCheckTest.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spec section 4.4: the database, not the token, decides on every request. */
@ExtendWith(OutputCaptureExtension.class)
class LiveUserCheckTest extends IntegrationTest {

    @Autowired JwtEncoder encoder;

    @Test
    void aDisabledUsersTokenIsRefusedOnItsNextRequest() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        mvc.perform(get("/api/products").header("Authorization", staff.bearer())).andExpect(status().isOk());

        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", staff.userId());

        mvc.perform(get("/api/products").header("Authorization", staff.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aDemotedOwnerLosesOwnerRightsOnTheNextRequest() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(get("/api/users").header("Authorization", owner.bearer())).andExpect(status().isOk());

        // The token still says OWNER; the database now says STAFF.
        jdbc.update("UPDATE app_user SET role = 'STAFF' WHERE id = ?", owner.userId());

        mvc.perform(get("/api/users").header("Authorization", owner.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/products").header("Authorization", owner.bearer())).andExpect(status().isOk());
    }

    @Test
    void aValidTokenForAUserWhoDoesNotExistIsRefused() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + token("999999", owner.shopId())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aTokenWhoseShopDoesNotMatchTheUserIsRefused() throws Exception {
        TestApi.Session a = api.registerShop("A", "a@shop.test");
        TestApi.Session b = api.registerShop("B", "b@shop.test");
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + token(Long.toString(a.userId()), b.shopId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutAShopClaimIsRefused() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + token(Long.toString(owner.userId()), null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + token("not-a-number", owner.shopId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestLogsStillNameTheUser(CapturedOutput output) throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(get("/api/products").header("Authorization", owner.bearer())).andExpect(status().isOk());
        assertThat(output.getOut()).contains("GET /api/products -> 200").contains("user=" + owner.userId());
    }

    /** Signed with the real key, so only the database lookup can refuse it. shopId may be null to leave it out. */
    private String token(String subject, Long shopId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(subject).issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES)).claim("role", "OWNER");
        if (shopId != null) {
            claims.claim("shopId", shopId);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
```

Create `src/test/java/dev/wrenz/stockroom/security/UserLookupFailureTest.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spec section 4.4: a database failure during the lookup is a 500, never a 401 that would log everyone out. */
class UserLookupFailureTest extends IntegrationTest {

    @MockitoBean CurrentUserLookup lookup;

    @Test
    void aFailingLookupIsA500InTheStandardShape() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        when(lookup.load(any())).thenThrow(new DataAccessResourceFailureException("database unreachable"));

        mvc.perform(get("/api/products").header("Authorization", owner.bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
```

In `src/test/java/dev/wrenz/stockroom/security/TokenCasesTest.java`, replace `missingToken` with:

```java
    @Test
    void missingToken() throws Exception {
        ResultActions result = mvc.perform(get("/api/users"));
        expectProblem(result, 401, "UNAUTHORIZED");
        // RFC 6750: a 401 from a Bearer-protected endpoint names the scheme.
        result.andExpect(header().string("WWW-Authenticate", "Bearer"));
    }
```

(`header()` comes from the existing static import `MockMvcResultMatchers.*`; if that file imports matchers one by one, add `import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;`.)

In `src/test/java/dev/wrenz/stockroom/config/OpenApiTest.java`, add:

```java
    @Test
    void theCallerIsNeverADocumentedParameter() throws Exception {
        // The @AuthenticationPrincipal argument is filled in by Spring Security, not by the client.
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/orders'].post.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/users'].get.parameters").doesNotExist());
    }
```

In `src/test/java/dev/wrenz/stockroom/user/AuthIntegrationTest.java`, change `.andExpect(jsonPath("$.expiresIn").value(3600))` to `.andExpect(jsonPath("$.expiresIn").value(900))`.

- [ ] **Step 2: Run them and watch them fail**

```bash
./mvnw -B test -Dtest='LiveUserCheckTest,UserLookupFailureTest,TokenCasesTest,OpenApiTest,AuthIntegrationTest'
```

Expected: compilation fails because `CurrentUserLookup` does not exist. (Once it compiles, the `LiveUserCheckTest` refusals, the `WWW-Authenticate` header and `expiresIn` 900 would all fail against today's code; `requestLogsStillNameTheUser` and `theCallerIsNeverADocumentedParameter` pass today and guard the change.)

- [ ] **Step 3: Add the lookup**

Create `src/main/java/dev/wrenz/stockroom/security/CurrentUserLookup.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.AppUserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Loads the caller's CURRENT state on every request (spec section 4.4). The token proves who signed in; the
 * database decides whether they may still act, and with which role. No cache: a cache would bring back the
 * stale window this exists to remove.
 */
@Component
public class CurrentUserLookup {

    private final AppUserRepository users;

    public CurrentUserLookup(AppUserRepository users) {
        this.users = users;
    }

    /** Throws InvalidBearerTokenException (a 401) if the user is gone, disabled, or not in the token's shop. */
    public AuthUser load(Jwt jwt) {
        long userId = userId(jwt);
        if (!(jwt.getClaim("shopId") instanceof Number shopId)) {
            throw new InvalidBearerTokenException("The token names no shop");
        }
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown user"));
        if (user.getShopId() != shopId.longValue() || !user.isActive()) {
            throw new InvalidBearerTokenException("The user is not active in this shop");
        }
        return new AuthUser(user.getId(), user.getShopId(), user.getRole());
    }

    private static long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("The token subject is not a user id");
        }
    }
}
```

- [ ] **Step 4: Add the authentication and the filter**

Create `src/main/java/dev/wrenz/stockroom/security/CurrentUserAuthentication.java`:

```java
package dev.wrenz.stockroom.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/** The caller as the database sees them right now: the role comes from the row, never from the token. */
public class CurrentUserAuthentication extends AbstractAuthenticationToken {

    private final AuthUser user;
    private final Jwt token;

    public CurrentUserAuthentication(AuthUser user, Jwt token) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        this.user = user;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public AuthUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return Long.toString(user.userId());
    }
}
```

Create `src/main/java/dev/wrenz/stockroom/security/CurrentUserFilter.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.common.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Runs right after Spring has checked the token's signature and expiry (spec section 4.4). Looks the caller up in
 * the database on every request, then swaps the token-only authentication for one built from that row.
 * A filter rather than a JWT converter so that BOTH failure kinds keep the standard error shape: a refusal goes
 * to the entry point (401), and a database error goes to the same resolver ProblemSecurityHandlers uses (500).
 * Not a @Component, so the servlet container doesn't register it a second time.
 */
class CurrentUserFilter extends OncePerRequestFilter {

    private final CurrentUserLookup lookup;
    private final AuthenticationEntryPoint entryPoint;
    private final HandlerExceptionResolver resolver;

    CurrentUserFilter(CurrentUserLookup lookup, AuthenticationEntryPoint entryPoint, HandlerExceptionResolver resolver) {
        this.lookup = lookup;
        this.entryPoint = entryPoint;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            AuthUser user;
            try {
                user = lookup.load(token.getToken());
            } catch (AuthenticationException ex) {
                SecurityContextHolder.clearContext();
                entryPoint.commence(request, response, ex);
                return;
            } catch (DataAccessException ex) {
                // A 500, never a 401: a 401 would log every user out because of a database hiccup.
                SecurityContextHolder.clearContext();
                resolver.resolveException(request, response, null, ex);
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new CurrentUserAuthentication(user, token.getToken()));
            SecurityContextHolder.setContext(context);
            request.setAttribute(RequestLoggingFilter.USER_ID_ATTRIBUTE, Long.toString(user.userId()));
        }
        chain.doFilter(request, response);
    }
}
```

Delete the old filter, whose job (the log attribute) the new one now does:

```bash
git rm src/main/java/dev/wrenz/stockroom/security/UserIdRequestAttributeFilter.java
```

- [ ] **Step 5: Wire it in, and stop the token granting roles**

In `src/main/java/dev/wrenz/stockroom/security/SecurityConfig.java`:

1. Change the method signature to:

```java
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems, CurrentUserLookup currentUsers,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
```

2. Replace `.addFilterAfter(new UserIdRequestAttributeFilter(), BearerTokenAuthenticationFilter.class);` with:

```java
                .addFilterAfter(new CurrentUserFilter(currentUsers, problems, resolver), BearerTokenAuthenticationFilter.class);
```

3. Replace the `jwtAuthenticationConverter()` method and its Javadoc with:

```java
    /** A token alone grants no role: CurrentUserFilter replaces this authentication with one built from the database. */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        return converter;
    }
```

4. Imports: remove `org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter`; add `org.springframework.beans.factory.annotation.Qualifier`, `org.springframework.web.servlet.HandlerExceptionResolver` and `java.util.List`.

- [ ] **Step 6: Change the principal type everywhere**

Replace `src/main/java/dev/wrenz/stockroom/security/AuthUser.java` with:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.user.Role;

/**
 * Who is calling, as loaded from the database on this request by CurrentUserFilter (spec section 4.4).
 * shopId never comes from the URL, body or query string.
 */
public record AuthUser(long userId, long shopId, Role role) {

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
```

Then update the three controllers:

```bash
for f in src/main/java/dev/wrenz/stockroom/order/OrderController.java \
         src/main/java/dev/wrenz/stockroom/product/ProductController.java \
         src/main/java/dev/wrenz/stockroom/user/UserController.java; do
  sed -i -e 's/@AuthenticationPrincipal Jwt jwt/@AuthenticationPrincipal AuthUser user/g' \
         -e 's/AuthUser\.from(jwt)/user/g' \
         -e '/^import org\.springframework\.security\.oauth2\.jwt\.Jwt;$/d' "$f"
done
grep -rn "AuthUser.from\|Principal Jwt" src/ || echo "none left"
```

Expected: `none left`.

- [ ] **Step 7: Add `WWW-Authenticate` to 401s and shorten the token lifetime**

In `src/main/java/dev/wrenz/stockroom/common/GlobalExceptionHandler.java`, replace `handleAuthentication` with:

```java
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        // RFC 6750: a 401 from a Bearer-protected endpoint names the scheme the client should use.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required"));
    }
```

In `src/main/resources/application.yml`, change `ttl: 1h` to `ttl: 15m`. In `src/main/java/dev/wrenz/stockroom/security/TokenService.java`, change the Javadoc of `issue` to: `/** Claims: sub = userId, shopId, role. The role claim is informational: CurrentUserFilter reads the role from the database. */`

- [ ] **Step 8: Run the tests, then the full suite**

```bash
./mvnw -B test -Dtest='LiveUserCheckTest,UserLookupFailureTest,TokenCasesTest,OpenApiTest,AuthIntegrationTest'
./mvnw -B verify
```

Expected: all pass, then `BUILD SUCCESS` with the previous count + 8 and 0 failures. If an existing test fails because it relied on the role claim, the test (not the code) is out of date: the role must come from the database.

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/dev/wrenz/stockroom/security src/main/java/dev/wrenz/stockroom/common/GlobalExceptionHandler.java \
  src/main/java/dev/wrenz/stockroom/order/OrderController.java src/main/java/dev/wrenz/stockroom/product/ProductController.java \
  src/main/java/dev/wrenz/stockroom/user/UserController.java src/main/resources/application.yml \
  src/test/java/dev/wrenz/stockroom/security src/test/java/dev/wrenz/stockroom/config/OpenApiTest.java \
  src/test/java/dev/wrenz/stockroom/user/AuthIntegrationTest.java
git commit -m "feat: every request is checked against the database; roles come from the row; 15-minute access tokens; WWW-Authenticate on 401"
```

---

### Task 3: `GET /api/me` and status in user lists

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/user/MeResponse.java`, `src/main/java/dev/wrenz/stockroom/user/MeController.java`
- Modify: `src/main/java/dev/wrenz/stockroom/user/UserResponse.java`, `src/main/java/dev/wrenz/stockroom/user/UserService.java`
- Create: `src/test/java/dev/wrenz/stockroom/user/MeTest.java`

**Interfaces:**
- Consumes: `AuthUser` principal (Task 2), `AppUser.getStatus()` (Task 1).
- Produces: `GET /api/me` → `MeResponse(long id, String email, Role role, UserStatus status, long shopId, String shopName)`; `UserResponse(long id, String email, Role role, UserStatus status, Instant createdAt)`; `UserService.me(AuthUser): MeResponse`; `UserService` constructor now also takes `ShopRepository`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/wrenz/stockroom/user/MeTest.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spec section 4.5: the Angular shell reads the current user from the database, not from token claims. */
class MeTest extends IntegrationTest {

    @Test
    void ownerAndStaffSeeThemselvesAsTheDatabaseDoes() throws Exception {
        TestApi.Session owner = api.registerShop("Corner Store", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");

        mvc.perform(get("/api/me").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.userId()))
                .andExpect(jsonPath("$.email").value("owner@shop.test"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.shopId").value(owner.shopId()))
                .andExpect(jsonPath("$.shopName").value("Corner Store"));
        mvc.perform(get("/api/me").header("Authorization", staff.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"));
    }

    @Test
    void meNeedsAToken() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theUserListShowsStatus() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(get("/api/users").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -B test -Dtest=MeTest`
Expected: `ownerAndStaffSeeThemselvesAsTheDatabaseDoes` fails with 404 (no `/api/me`), and `theUserListShowsStatus` fails (no `status` field).

- [ ] **Step 3: Implement**

Create `src/main/java/dev/wrenz/stockroom/user/MeResponse.java`:

```java
package dev.wrenz.stockroom.user;

/** The signed-in user as the database sees them now (spec section 4.5). */
public record MeResponse(long id, String email, Role role, UserStatus status, long shopId, String shopName) {}
```

Create `src/main/java/dev/wrenz/stockroom/user/MeController.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** For owners and staff: who am I, in which shop, with which role right now. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserService users;

    public MeController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthUser user) {
        return users.me(user);
    }
}
```

Replace `src/main/java/dev/wrenz/stockroom/user/UserResponse.java` with:

```java
package dev.wrenz.stockroom.user;

import java.time.Instant;

public record UserResponse(long id, String email, Role role, UserStatus status, Instant createdAt) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
```

In `src/main/java/dev/wrenz/stockroom/user/UserService.java`:

1. Add a `private final ShopRepository shops;` field, take it as the second constructor parameter (`AppUserRepository users, ShopRepository shops, PasswordEncoder encoder`) and assign it.
2. Add imports `dev.wrenz.stockroom.common.NotFoundException`, `dev.wrenz.stockroom.shop.Shop`, `dev.wrenz.stockroom.shop.ShopRepository`.
3. Add the method:

```java
    @Transactional(readOnly = true)
    public MeResponse me(AuthUser caller) {
        AppUser user = users.findById(caller.userId()).orElseThrow(() -> new NotFoundException("User", caller.userId()));
        Shop shop = shops.findById(caller.shopId()).orElseThrow(() -> new NotFoundException("Shop", caller.shopId()));
        return new MeResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus(), shop.getId(), shop.getName());
    }
```

- [ ] **Step 4: Run the test and the full suite**

```bash
./mvnw -B test -Dtest=MeTest
./mvnw -B verify
```

Expected: PASS, then `BUILD SUCCESS` with the previous count + 3.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/wrenz/stockroom/user src/test/java/dev/wrenz/stockroom/user/MeTest.java
git commit -m "feat: GET /api/me reads the current user from the database; user lists show status"
```

---

### Task 4: Refresh tokens: rotation and reuse detection

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/session/StoredRefreshToken.java`
- Create: `src/main/java/dev/wrenz/stockroom/session/RefreshTokenRepository.java`
- Create: `src/main/java/dev/wrenz/stockroom/session/SessionTokens.java`
- Create: `src/main/java/dev/wrenz/stockroom/session/RefreshCookies.java`
- Create: `src/main/java/dev/wrenz/stockroom/session/RefreshTokenService.java`
- Create: `src/main/java/dev/wrenz/stockroom/common/RefreshRejectedException.java`
- Modify: `src/main/java/dev/wrenz/stockroom/common/GlobalExceptionHandler.java`
- Modify: `src/main/java/dev/wrenz/stockroom/user/AuthService.java`, `src/main/java/dev/wrenz/stockroom/user/AuthController.java`
- Modify: `src/main/java/dev/wrenz/stockroom/security/SecurityConfig.java` (permit the two cookie endpoints)
- Modify: `src/test/java/dev/wrenz/stockroom/support/TestApi.java`
- Create: `src/test/java/dev/wrenz/stockroom/session/RefreshTokenTest.java`

**Interfaces:**
- Consumes: `AppUser.isActive()` (Task 1), `TokenService.issue(AppUser): TokenResponse`.
- Produces:
  - `RefreshTokenService.startSession(AppUser): SessionTokens`, `rotate(String refreshToken): SessionTokens` (throws `RefreshRejectedException`), `logout(String)`, `endAllSessions(long userId)`; constants `TOKEN_LIFETIME` (14 days), `SESSION_LIFETIME` (30 days), `MAX_SESSIONS_PER_USER` (50).
  - `record SessionTokens(TokenResponse access, String refreshToken)`.
  - `RefreshCookies.NAME = "stockroom_refresh"`, `RefreshCookies.issue(String)`, `RefreshCookies.cleared()`.
  - `RefreshRejectedException.invalid()` / `.reused()` with codes `REFRESH_INVALID` / `REFRESH_REUSED`.
  - `AuthService.register(...)` and `login(...)` now return `SessionTokens`.
  - `TestApi.Session(String token, long userId, long shopId, String refreshToken)`, `TestApi.refresh(String): ResultActions`, `TestApi.refreshCookieValue(String setCookieHeader): String`.

- [ ] **Step 1: Give the test helper the refresh cookie**

In `src/test/java/dev/wrenz/stockroom/support/TestApi.java`:

1. Replace the `Session` record with:

```java
    public record Session(String token, long userId, long shopId, String refreshToken) {
        public String bearer() { return "Bearer " + token; }
    }
```

2. In `registerShop` and `login`, end the chain with `.andReturn();` and `return session(result);` instead of reading the body string. For example, `login` becomes:

```java
    public Session login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return session(result);
    }
```

(and `registerShop` the same, with `status().isCreated()`).

3. Replace the private `session(String json)` method with:

```java
    private static Session session(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        return new Session(JsonPath.read(json, "$.accessToken"),
                ((Number) JsonPath.read(json, "$.userId")).longValue(),
                ((Number) JsonPath.read(json, "$.shopId")).longValue(),
                refreshCookieValue(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)));
    }

    /** The stockroom_refresh value in a Set-Cookie header, or null if there is none. */
    public static String refreshCookieValue(String setCookie) {
        if (setCookie == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("stockroom_refresh=([^;]*)").matcher(setCookie);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** POST /api/auth/refresh with the cookie (or none if refreshToken is null), like a browser on our own origin. */
    public ResultActions refresh(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/refresh");
        if (refreshToken != null) {
            request.cookie(new Cookie("stockroom_refresh", refreshToken));
        }
        return mvc.perform(request);
    }
```

4. Add imports: `jakarta.servlet.http.Cookie`, `org.springframework.http.HttpHeaders`, `org.springframework.test.web.servlet.MvcResult`, `org.springframework.test.web.servlet.ResultActions`, `org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder`, `java.util.regex.Matcher`, `java.util.regex.Pattern`.

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/dev/wrenz/stockroom/session/RefreshTokenTest.java`:

```java
package dev.wrenz.stockroom.session;

import com.jayway.jsonpath.JsonPath;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spec section 4.3: the cookie, rotation and reuse detection. */
class RefreshTokenTest extends IntegrationTest {

    @Test
    void loginAndRegisterSetAHardenedCookie() throws Exception {
        String register = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"Shop\",\"email\":\"owner@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        // MockMvc does not enforce cookie flags, so they are asserted one by one.
        for (String cookie : List.of(register, login)) {
            assertThat(cookie).startsWith("stockroom_refresh=").contains("HttpOnly").contains("Secure")
                    .contains("SameSite=Strict").contains("Path=/api/auth").contains("Max-Age=1209600");
        }
        assertThat(TestApi.refreshCookieValue(register)).hasSize(43).isNotEqualTo(TestApi.refreshCookieValue(login));
    }

    @Test
    void onlyAHashIsStored() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", owner.userId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE token_hash = ?", owner.refreshToken())).isZero();
    }

    @Test
    void refreshRotatesTheTokenAndTheNewAccessTokenWorks() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");

        MvcResult first = api.refresh(owner.refreshToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)).andReturn();
        String next = TestApi.refreshCookieValue(first.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(next).hasSize(43).isNotEqualTo(owner.refreshToken());

        String access = JsonPath.read(first.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        api.refresh(next).andExpect(status().isOk());
    }

    @Test
    void replayingAUsedTokenEndsTheWholeSession() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        String next = TestApi.refreshCookieValue(api.refresh(owner.refreshToken())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE));

        api.refresh(owner.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_REUSED"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        // The successor was revoked with its family, and that revocation was committed despite the 401.
        api.refresh(next).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void deadTokensAreInvalid() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        api.refresh(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
        api.refresh("not-a-real-token").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));

        jdbc.update("UPDATE refresh_token SET expires_at = now() - interval '1 second' WHERE user_id = ?", owner.userId());
        api.refresh(owner.refreshToken()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));

        TestApi.Session again = api.login("owner@shop.test", TestApi.PASSWORD);
        jdbc.update("UPDATE refresh_token SET family_expires_at = now() - interval '1 second' WHERE user_id = ?", again.userId());
        api.refresh(again.refreshToken()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void aDisabledUserCannotRefresh() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", staff.userId());
        api.refresh(staff.refreshToken()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void refreshIssuesTheCurrentRole() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        jdbc.update("UPDATE app_user SET role = 'OWNER' WHERE id = ?", staff.userId());
        api.refresh(staff.refreshToken()).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void aDisabledAccountGetsTheSameLoginErrorAsAWrongPassword() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        api.createStaff(owner, "staff@shop.test");
        String wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@shop.test\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE email = 'staff@shop.test'");

        String disabled = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(disabled, "$.code")).isEqualTo("UNAUTHORIZED");
        assertThat(JsonPath.<String>read(disabled, "$.detail")).isEqualTo(JsonPath.read(wrongPassword, "$.detail"));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `./mvnw -B test -Dtest=RefreshTokenTest`
Expected: the tests fail (no cookie in responses, `/api/auth/refresh` answers 401 or 404, the disabled account still logs in).

- [ ] **Step 4: Add the exception and its handler**

Create `src/main/java/dev/wrenz/stockroom/common/RefreshRejectedException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A refresh that can't continue the session (spec section 4.3). The handler also deletes the cookie. */
public class RefreshRejectedException extends ApiException {

    private RefreshRejectedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message, Map.of());
    }

    public static RefreshRejectedException invalid() {
        return new RefreshRejectedException("REFRESH_INVALID", "Your session has ended. Please log in again.");
    }

    /** A used token came back, so it may have been stolen. Its whole session has been revoked. */
    public static RefreshRejectedException reused() {
        return new RefreshRejectedException("REFRESH_REUSED", "This session was ended to protect your account. Please log in again.");
    }
}
```

In `GlobalExceptionHandler`, add (with `import dev.wrenz.stockroom.session.RefreshCookies;`):

```java
    /** A rejected refresh also deletes the browser's cookie, so it stops sending a dead token. */
    @ExceptionHandler(RefreshRejectedException.class)
    ResponseEntity<ProblemDetail> handleRefreshRejected(RefreshRejectedException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.SET_COOKIE, RefreshCookies.cleared().toString())
                .body(problem(ex.getStatus(), ex.getCode(), ex.getMessage()));
    }
```

- [ ] **Step 5: Add the session package**

Create `src/main/java/dev/wrenz/stockroom/session/StoredRefreshToken.java`:

```java
package dev.wrenz.stockroom.session;

import java.time.Instant;
import java.util.UUID;

/** One row of refresh_token. usedAt and revokedAt stay null until the token is rotated or revoked. */
public record StoredRefreshToken(long id, long userId, long shopId, UUID familyId, Instant expiresAt,
                                 Instant familyExpiresAt, Instant usedAt, Instant revokedAt) {

    public boolean isUsed() { return usedAt != null; }

    public boolean isRevoked() { return revokedAt != null; }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now) || !familyExpiresAt.isAfter(now);
    }
}
```

Create `src/main/java/dev/wrenz/stockroom/session/RefreshTokenRepository.java`:

```java
package dev.wrenz.stockroom.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL, because rotation depends on exact statements: a conditional UPDATE decides which request wins, the
 * same idiom as StockRepository (spec section 4.3).
 */
@Repository
public class RefreshTokenRepository {

    private static final RowMapper<StoredRefreshToken> ROW = (rs, n) -> new StoredRefreshToken(
            rs.getLong("id"), rs.getLong("user_id"), rs.getLong("shop_id"), rs.getObject("family_id", UUID.class),
            rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("family_expires_at").toInstant(),
            instant(rs.getTimestamp("used_at")), instant(rs.getTimestamp("revoked_at")));

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, long shopId, UUID familyId, String tokenHash, Instant expiresAt, Instant familyExpiresAt) {
        jdbc.update("""
                INSERT INTO refresh_token (shop_id, user_id, family_id, token_hash, expires_at, family_expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, shopId, userId, familyId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(familyExpiresAt));
    }

    public Optional<StoredRefreshToken> findByHash(String tokenHash) {
        return jdbc.query("SELECT * FROM refresh_token WHERE token_hash = ?", ROW, tokenHash).stream().findFirst();
    }

    /** True for exactly one caller: the row is marked used only if nobody used or revoked it first. */
    public boolean markUsed(long id) {
        return jdbc.update("""
                UPDATE refresh_token SET used_at = now()
                WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL
                """, id) == 1;
    }

    /**
     * Statement 1 of a logout: it touches the presented row, so it waits for any refresh of that token still in
     * flight. Returns the family for statement 2 (spec 4.3, "Races between refresh and revocation").
     */
    public Optional<UUID> revokeToken(String tokenHash) {
        return jdbc.query("""
                UPDATE refresh_token SET revoked_at = COALESCE(revoked_at, now())
                WHERE token_hash = ? RETURNING family_id
                """, (rs, n) -> rs.getObject("family_id", UUID.class), tokenHash).stream().findFirst();
    }

    /** Statement 2: a new statement, so under READ COMMITTED it also sees a successor committed meanwhile. */
    public void revokeFamily(UUID familyId) {
        jdbc.update("UPDATE refresh_token SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL", familyId);
    }

    public void revokeAllForUser(long userId) {
        jdbc.update("UPDATE refresh_token SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    /** Login housekeeping (F1 runs no scheduled jobs). Used rows of live sessions stay: reuse detection needs them. */
    public void deleteDeadTokens(long userId) {
        jdbc.update("""
                DELETE FROM refresh_token
                WHERE user_id = ? AND (revoked_at IS NOT NULL OR expires_at <= now() OR family_expires_at <= now())
                """, userId);
    }

    /** Keeps the user's newest `keep` live sessions and revokes the older ones. Each live session has one unused token. */
    public void revokeOldestSessions(long userId, int keep) {
        jdbc.update("""
                UPDATE refresh_token SET revoked_at = now()
                WHERE revoked_at IS NULL AND family_id IN (
                    SELECT family_id FROM refresh_token
                    WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                      AND expires_at > now() AND family_expires_at > now()
                    ORDER BY issued_at DESC, id DESC
                    OFFSET ?)
                """, userId, keep);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
```

Create `src/main/java/dev/wrenz/stockroom/session/SessionTokens.java`:

```java
package dev.wrenz.stockroom.session;

import dev.wrenz.stockroom.user.TokenResponse;

/** What login, register and refresh hand out: the access token (response body) and the refresh token (cookie). */
public record SessionTokens(TokenResponse access, String refreshToken) {}
```

Create `src/main/java/dev/wrenz/stockroom/session/RefreshCookies.java`:

```java
package dev.wrenz.stockroom.session;

import org.springframework.http.ResponseCookie;

/**
 * The refresh cookie (spec section 4.1): HttpOnly (no script can read it), Secure (HTTPS only), SameSite=Strict
 * (never sent by another site's request) and Path=/api/auth (never sent to the rest of the API).
 */
public final class RefreshCookies {

    public static final String NAME = "stockroom_refresh";
    private static final String PATH = "/api/auth";

    private RefreshCookies() {}

    public static ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(RefreshTokenService.TOKEN_LIFETIME).build();
    }

    /** Tells the browser to delete the cookie. */
    public static ResponseCookie cleared() {
        return base("").maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
```

Create `src/main/java/dev/wrenz/stockroom/session/RefreshTokenService.java`:

```java
package dev.wrenz.stockroom.session;

import dev.wrenz.stockroom.common.RefreshRejectedException;
import dev.wrenz.stockroom.security.TokenService;
import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * "Stay logged in" (spec section 4.3). A refresh token works once: each use swaps it for a new one in the same
 * family (session). If a used token ever comes back it may have been stolen, so the whole family is revoked.
 */
@Service
public class RefreshTokenService {

    public static final Duration TOKEN_LIFETIME = Duration.ofDays(14);
    public static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    public static final int MAX_SESSIONS_PER_USER = 50;

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokens;
    private final AppUserRepository users;
    private final TokenService tokens;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokens, AppUserRepository users, TokenService tokens) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tokens = tokens;
    }

    /** Login and register: a new session. Also tidies this user's dead rows, since F1 runs no scheduled jobs. */
    @Transactional
    public SessionTokens startSession(AppUser user) {
        refreshTokens.deleteDeadTokens(user.getId());
        Instant now = Instant.now();
        String refreshToken = newToken();
        refreshTokens.insert(user.getId(), user.getShopId(), UUID.randomUUID(), hash(refreshToken),
                now.plus(TOKEN_LIFETIME), now.plus(SESSION_LIFETIME));
        refreshTokens.revokeOldestSessions(user.getId(), MAX_SESSIONS_PER_USER);
        return new SessionTokens(tokens.issue(user), refreshToken);
    }

    /**
     * POST /api/auth/refresh. noRollbackFor: when a reused token revokes its family, that revocation must be
     * committed even though the answer is a 401.
     */
    @Transactional(noRollbackFor = RefreshRejectedException.class)
    public SessionTokens rotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw RefreshRejectedException.invalid();
        }
        String hash = hash(refreshToken);
        StoredRefreshToken token = refreshTokens.findByHash(hash).orElseThrow(RefreshRejectedException::invalid);
        Instant now = Instant.now();
        if (token.isRevoked() || token.isExpired(now)) {
            throw RefreshRejectedException.invalid();
        }
        AppUser user = users.findById(token.userId()).filter(AppUser::isActive)
                .orElseThrow(RefreshRejectedException::invalid);
        if (!refreshTokens.markUsed(token.id())) {
            // The row was used or revoked after we read it, or was already used. Re-read to tell which.
            StoredRefreshToken current = refreshTokens.findByHash(hash).orElseThrow(RefreshRejectedException::invalid);
            if (current.isUsed()) {
                refreshTokens.revokeFamily(current.familyId());
                throw RefreshRejectedException.reused();
            }
            throw RefreshRejectedException.invalid();
        }
        String next = newToken();
        refreshTokens.insert(user.getId(), user.getShopId(), token.familyId(), hash(next),
                now.plus(TOKEN_LIFETIME), token.familyExpiresAt());
        return new SessionTokens(tokens.issue(user), next);
    }

    /** POST /api/auth/logout: revokes the cookie's whole session. A missing or unknown token is ignored. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokens.revokeToken(hash(refreshToken)).ifPresent(refreshTokens::revokeFamily);
    }

    /** Disable and re-enable (spec section 4.5): every session of this user ends now. */
    @Transactional
    public void endAllSessions(long userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    /** SHA-256, base64url without padding: always 43 characters, the width of the column. */
    static String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return BASE64URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
```

- [ ] **Step 6: Issue sessions at login and register, refuse disabled accounts**

In `src/main/java/dev/wrenz/stockroom/user/AuthService.java`:

1. Replace the `TokenService tokens` field and constructor parameter with `RefreshTokenService sessions` (import `dev.wrenz.stockroom.session.RefreshTokenService` and `dev.wrenz.stockroom.session.SessionTokens`; remove the `TokenService` import).
2. Replace `register` and `login` with:

```java
    /** Shop and owner are created together or not at all. A duplicate email becomes 409 EMAIL_TAKEN. */
    @Transactional
    public SessionTokens register(RegisterRequest request, String clientIp) {
        limiter.checkIp(clientIp);
        ReservedEmails.requireNotReserved(request.email());
        Shop shop = shops.save(new Shop(request.shopName()));
        AppUser owner = users.save(new AppUser(shop.getId(), request.email(), encoder.encode(request.password()), Role.OWNER));
        return sessions.startSession(owner);
    }

    /**
     * Same 401 for an unknown email, a wrong password and a disabled account, so login can't reveal which.
     * Not read-only any more: a successful login writes a refresh token.
     */
    @Transactional
    public SessionTokens login(LoginRequest request, String clientIp) {
        limiter.checkIp(clientIp);
        limiter.checkEmailNotLocked(request.email());
        Optional<AppUser> user = users.findByEmail(request.email());
        boolean matches = encoder.matches(request.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches || !user.get().isActive()) {
            limiter.recordFailure(request.email());
            throw new UnauthorizedException("Invalid email or password");
        }
        return sessions.startSession(user.get());
    }
```

Replace `src/main/java/dev/wrenz/stockroom/user/AuthController.java` with:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.ClientIpResolver;
import dev.wrenz.stockroom.security.RateLimitProperties;
import dev.wrenz.stockroom.session.RefreshCookies;
import dev.wrenz.stockroom.session.RefreshTokenService;
import dev.wrenz.stockroom.session.SessionTokens;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Empty @SecurityRequirements: Swagger UI must not attach the (possibly expired) token to these calls. */
@RestController
@RequestMapping("/api/auth")
@SecurityRequirements
public class AuthController {

    private final AuthService auth;
    private final RefreshTokenService sessions;
    private final boolean trustForwardedFor;

    public AuthController(AuthService auth, RefreshTokenService sessions, RateLimitProperties limits) {
        this.auth = auth;
        this.sessions = sessions;
        this.trustForwardedFor = limits.trustForwardedFor();
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http,
                                  HttpServletResponse response) {
        return withCookie(auth.register(request, clientIp(http)), response);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        return withCookie(auth.login(request, clientIp(http)), response);
    }

    /** Reads only the cookie (no body, no Authorization header) and swaps it for a new one (spec section 4.3). */
    @PostMapping("/refresh")
    public TokenResponse refresh(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
                                 HttpServletResponse response) {
        return withCookie(sessions.rotate(refreshToken), response);
    }

    private TokenResponse withCookie(SessionTokens session, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, RefreshCookies.issue(session.refreshToken()).toString());
        return session.access();
    }

    private String clientIp(HttpServletRequest http) {
        return ClientIpResolver.resolve(http, trustForwardedFor);
    }
}
```

Check the original file's imports match the package locations above (for example `ClientIpResolver` and `RateLimitProperties` in `dev.wrenz.stockroom.security`); keep whatever the original used.

In `SecurityConfig`, change the first `permitAll` matcher to:

```java
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
```

- [ ] **Step 7: Run the tests and the full suite**

```bash
./mvnw -B test -Dtest=RefreshTokenTest
./mvnw -B verify
```

Expected: PASS, then `BUILD SUCCESS` with the previous count + 8.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/wrenz/stockroom/session src/main/java/dev/wrenz/stockroom/common \
  src/main/java/dev/wrenz/stockroom/user/AuthService.java src/main/java/dev/wrenz/stockroom/user/AuthController.java \
  src/main/java/dev/wrenz/stockroom/security/SecurityConfig.java \
  src/test/java/dev/wrenz/stockroom/support/TestApi.java src/test/java/dev/wrenz/stockroom/session/RefreshTokenTest.java
git commit -m "feat: refresh tokens in an HttpOnly cookie, rotated on every use; a reused token revokes its whole session"
```

---

### Task 5: Logout, forged-request protection, refresh rate limit, housekeeping and races

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/security/FetchMetadata.java`
- Modify: `src/main/java/dev/wrenz/stockroom/security/LoginRateLimiter.java`, `security/RateLimitProperties.java`, `security/SecurityConfig.java` (CSRF comment)
- Modify: `src/main/java/dev/wrenz/stockroom/user/AuthController.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/dev/wrenz/stockroom/support/TestApi.java`
- Create: `src/test/java/dev/wrenz/stockroom/session/SessionProtectionTest.java`
- Create: `src/test/java/dev/wrenz/stockroom/concurrency/SessionConcurrencyTest.java`

**Interfaces:**
- Consumes: `RefreshTokenService` (Task 4), `RefreshCookies`.
- Produces: `POST /api/auth/logout` (204); `FetchMetadata.requireSameOrigin(HttpServletRequest)`; `LoginRateLimiter.checkRefreshIp(String)`; property `stockroom.rate-limit.max-refreshes-per-ip`; `TestApi.logout(String): ResultActions`.

- [ ] **Step 1: Add the logout helper**

In `TestApi`, add:

```java
    public ResultActions logout(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/logout");
        if (refreshToken != null) {
            request.cookie(new Cookie("stockroom_refresh", refreshToken));
        }
        return mvc.perform(request);
    }
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/dev/wrenz/stockroom/session/SessionProtectionTest.java`:

```java
package dev.wrenz.stockroom.session;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spec sections 4.3 and 4.6: logout, forged requests, the refresh rate limit and login housekeeping. */
class SessionProtectionTest extends IntegrationTest {

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        api.logout(owner.refreshToken())
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        api.refresh(owner.refreshToken()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void logoutWithoutACookieIsStill204() throws Exception {
        api.logout(null).andExpect(status().isNoContent());
        api.logout("not-a-real-token").andExpect(status().isNoContent());
    }

    @Test
    void requestsFromAnotherSiteAreRefused() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        Cookie cookie = new Cookie("stockroom_refresh", owner.refreshToken());
        for (String site : List.of("cross-site", "same-site", "none")) {
            mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Sec-Fetch-Site", site))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(post("/api/auth/logout").cookie(cookie).header("Sec-Fetch-Site", site))
                    .andExpect(status().isForbidden());
        }
        // The refused attempts changed nothing: the session still works from our own origin.
        mvc.perform(post("/api/auth/refresh").cookie(cookie).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
    }

    @Test
    void refreshHasItsOwnRateLimit() throws Exception {
        for (int i = 0; i < 60; i++) {
            api.refresh("unknown-" + i).andExpect(status().isUnauthorized());
        }
        api.refresh("unknown-61").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // Logging in has a separate budget.
        api.registerShop("Shop", "owner@shop.test");
    }

    @Test
    void loginTidiesDeadTokensAndKeepsAtMostFiftySessions() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        jdbc.update("UPDATE refresh_token SET revoked_at = now() WHERE user_id = ?", owner.userId());
        for (int i = 0; i < 50; i++) {
            jdbc.update("""
                    INSERT INTO refresh_token (shop_id, user_id, family_id, token_hash, issued_at, expires_at, family_expires_at)
                    VALUES (?, ?, gen_random_uuid(), ?, now() - make_interval(mins => ?), now() + interval '1 day', now() + interval '1 day')
                    """, owner.shopId(), owner.userId(), "%043d".formatted(i), 100 - i);
        }

        api.login("owner@shop.test", TestApi.PASSWORD);

        // The revoked row is gone, the new login makes 51 live sessions, and the oldest one is revoked.
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", owner.userId())).isEqualTo(51);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", owner.userId())).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE token_hash = ? AND revoked_at IS NOT NULL", "%043d".formatted(0))).isEqualTo(1);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
```

Create `src/test/java/dev/wrenz/stockroom/concurrency/SessionConcurrencyTest.java`:

```java
package dev.wrenz.stockroom.concurrency;

import dev.wrenz.stockroom.common.RefreshRejectedException;
import dev.wrenz.stockroom.session.RefreshTokenService;
import dev.wrenz.stockroom.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec section 4.3: races between rotation and revocation, with real threads against real PostgreSQL. */
class SessionConcurrencyTest extends IntegrationTest {

    @Autowired RefreshTokenService sessions;

    ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    /** Make sure no worker transaction outlives this test and overlaps the next test's TRUNCATE. */
    @AfterEach
    void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(45, SECONDS)).as("worker threads still running").isTrue();
    }

    @Test
    void twoRefreshesWithTheSameTokenExactlyOneWins() throws Exception {
        String token = api.registerShop("Shop", "owner@shop.test").refreshToken();
        CountDownLatch start = new CountDownLatch(1);
        Future<String> a = pool.submit(() -> rotate(start, token));
        Future<String> b = pool.submit(() -> rotate(start, token));
        start.countDown();

        assertThat(List.of(a.get(30, SECONDS), b.get(30, SECONDS))).containsExactlyInAnyOrder("ROTATED", "REFRESH_REUSED");
    }

    @Test
    void aLogoutRacingARefreshLeavesNoWorkingToken() throws Exception {
        String token = api.registerShop("Shop", "owner@shop.test").refreshToken();
        CountDownLatch start = new CountDownLatch(1);
        Future<String> refresh = pool.submit(() -> {
            start.await();
            try {
                return sessions.rotate(token).refreshToken();
            } catch (RefreshRejectedException e) {
                return null;
            }
        });
        Future<Void> logout = pool.submit(() -> {
            start.await();
            sessions.logout(token);
            return null;
        });
        start.countDown();
        String successor = refresh.get(30, SECONDS);
        logout.get(30, SECONDS);

        // Whichever ran first, nothing in that session works afterwards.
        assertThatThrownBy(() -> sessions.rotate(token)).isInstanceOf(RefreshRejectedException.class);
        if (successor != null) {
            assertThatThrownBy(() -> sessions.rotate(successor)).isInstanceOf(RefreshRejectedException.class);
        }
    }

    private String rotate(CountDownLatch start, String token) throws InterruptedException {
        start.await();
        try {
            sessions.rotate(token);
            return "ROTATED";
        } catch (RefreshRejectedException e) {
            return e.getCode();
        }
    }
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `./mvnw -B test -Dtest='SessionProtectionTest,SessionConcurrencyTest'`
Expected: `logout*` tests fail (no logout endpoint), `requestsFromAnotherSiteAreRefused` gets 200 instead of 403, `refreshHasItsOwnRateLimit` never sees 429. `loginTidiesDeadTokensAndKeepsAtMostFiftySessions` and the two concurrency tests may already pass on Task 4's code: they pin behaviour this task must not break. If `aLogoutRacingARefreshLeavesNoWorkingToken` fails, that is a real defect in Task 4's revocation order: fix `RefreshTokenService.logout`, not the test.

- [ ] **Step 4: Implement**

Create `src/main/java/dev/wrenz/stockroom/security/FetchMetadata.java`:

```java
package dev.wrenz.stockroom.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Second defence for the two cookie endpoints, after SameSite=Strict (spec section 4.6). Browsers report where a
 * request came from in Sec-Fetch-Site; anything but our own origin is refused. Non-browser clients don't send the
 * header and can't be tricked into sending someone else's cookie, so a missing header passes.
 */
public final class FetchMetadata {

    private FetchMetadata() {}

    public static void requireSameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin")) {
            throw new AccessDeniedException("Cross-site request refused");
        }
    }
}
```

In `RateLimitProperties`, add a final record component `int maxRefreshesPerIp` (after `boolean trustForwardedFor`). In `application.yml`, under `stockroom.rate-limit`, add `max-refreshes-per-ip: 60`.

In `LoginRateLimiter`:

1. Add fields `private final Cache<String, AtomicInteger> refreshesByIp;` and `private final int maxRefreshesPerIp;`, initialised in the constructor like the others (`Caffeine.newBuilder().maximumSize(properties.maxKeys()).expireAfterWrite(properties.window()).build()` and `properties.maxRefreshesPerIp()`).
2. Add:

```java
    /** Refreshes have their own budget, so normal use never eats into the login limit (spec section 4.6). */
    public void checkRefreshIp(String ip) {
        if (refreshesByIp.get(ip, key -> new AtomicInteger()).incrementAndGet() > maxRefreshesPerIp) {
            throw new RateLimitedException();
        }
    }
```

3. In `clear()`, add `refreshesByIp.invalidateAll();`.

In `AuthController`:

1. Add a `LoginRateLimiter limiter` field and constructor parameter (`AuthService auth, RefreshTokenService sessions, LoginRateLimiter limiter, RateLimitProperties limits`), and imports `dev.wrenz.stockroom.security.FetchMetadata`, `dev.wrenz.stockroom.security.LoginRateLimiter`.
2. Replace the `refresh` method and add `logout`:

```java
    /** Reads only the cookie (no body, no Authorization header) and swaps it for a new one (spec section 4.3). */
    @PostMapping("/refresh")
    public TokenResponse refresh(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
                                 HttpServletRequest http, HttpServletResponse response) {
        FetchMetadata.requireSameOrigin(http);
        limiter.checkRefreshIp(clientIp(http));
        return withCookie(sessions.rotate(refreshToken), response);
    }

    /** Ends the cookie's session and deletes the cookie. Always 204, even without a cookie. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
                       HttpServletRequest http, HttpServletResponse response) {
        FetchMetadata.requireSameOrigin(http);
        sessions.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, RefreshCookies.cleared().toString());
    }
```

In `SecurityConfig`, replace the comment above `.csrf(AbstractHttpConfigurer::disable)` with:

```java
                // CSRF tokens stay off. The API authenticates with a Bearer header, which a forged cross-site request
                // can't attach; the two cookie endpoints (/api/auth/refresh, /api/auth/logout) are protected by
                // SameSite=Strict plus FetchMetadata's Sec-Fetch-Site check instead (spec section 4.6).
```

If any test builds `RateLimitProperties` or `LoginRateLimiter` directly (`grep -rn "new RateLimitProperties\|new LoginRateLimiter" src/test`), add the new last argument `60` there.

- [ ] **Step 5: Run the tests and the full suite**

```bash
./mvnw -B test -Dtest='SessionProtectionTest,SessionConcurrencyTest,RefreshTokenTest,RateLimitTest'
./mvnw -B verify
```

Expected: PASS, then `BUILD SUCCESS` with the previous count + 7.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/wrenz/stockroom/security src/main/java/dev/wrenz/stockroom/user/AuthController.java \
  src/main/resources/application.yml src/test/java/dev/wrenz/stockroom/support/TestApi.java \
  src/test/java/dev/wrenz/stockroom/session/SessionProtectionTest.java \
  src/test/java/dev/wrenz/stockroom/concurrency/SessionConcurrencyTest.java
git commit -m "feat: logout; cookie endpoints refuse cross-site requests; refresh has its own rate limit; race tests"
```

---

### Task 6: Owners manage staff (`PATCH /api/users/{id}`)

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/user/UpdateUserRequest.java`
- Modify: `src/main/java/dev/wrenz/stockroom/user/AppUserRepository.java`, `user/UserService.java`, `user/UserController.java`, `user/ReservedEmails.java`
- Create: `src/test/java/dev/wrenz/stockroom/user/UserManagementTest.java`
- Create: `src/test/java/dev/wrenz/stockroom/concurrency/UserChangeConcurrencyTest.java`

**Interfaces:**
- Consumes: `AppUser.changeRole/changeStatus` (Task 1), `RefreshTokenService.endAllSessions(long)` (Task 4), `AuthUser` principal (Task 2).
- Produces: `PATCH /api/users/{id}` with `UpdateUserRequest(Role role, UserStatus status)` → `UserResponse`; `UserService.update(AuthUser, long, UpdateUserRequest): UserResponse`; `AppUserRepository.findByIdAndShopId(long, long)`, `lockActiveOwnerIds(long): List<Long>`; `ReservedEmails.isDemoAccount(String)`; `UserService` constructor `(AppUserRepository, ShopRepository, PasswordEncoder, RefreshTokenService)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/dev/wrenz/stockroom/user/UserManagementTest.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Spec section 4.5: role and status changes, and their guard rails. */
class UserManagementTest extends IntegrationTest {

    @Autowired PasswordEncoder encoder;

    TestApi.Session owner;
    TestApi.Session staff;

    @BeforeEach
    void shop() throws Exception {
        owner = api.registerShop("Shop", "owner@shop.test");
        staff = api.createStaff(owner, "staff@shop.test");
    }

    @Test
    void disablingStaffEndsTheirAccessAtOnce() throws Exception {
        change(owner, staff.userId(), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get("/api/products").header("Authorization", staff.bearer())).andExpect(status().isUnauthorized());
        api.refresh(staff.refreshToken()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void reEnablingRestoresLoginButNotOldSessions() throws Exception {
        change(owner, staff.userId(), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        change(owner, staff.userId(), "{\"status\":\"ACTIVE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        api.refresh(staff.refreshToken()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
        api.login("staff@shop.test", TestApi.PASSWORD);
    }

    @Test
    void promotingAndDemotingTakesEffectOnTheNextRequest() throws Exception {
        change(owner, staff.userId(), "{\"role\":\"OWNER\"}").andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(get("/api/users").header("Authorization", staff.bearer())).andExpect(status().isOk());
        change(owner, staff.userId(), "{\"role\":\"STAFF\"}").andExpect(status().isOk());
        mvc.perform(get("/api/users").header("Authorization", staff.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void anOwnerCannotChangeThemselves() throws Exception {
        change(owner, owner.userId(), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_CHANGE_SELF"));
    }

    @Test
    void anEmptyChangeIsRejected() throws Exception {
        change(owner, staff.userId(), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void repeatingTheCurrentValuesChangesNothing() throws Exception {
        change(owner, staff.userId(), "{\"role\":\"STAFF\",\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        // No status change, so the staff member's session is untouched.
        api.refresh(staff.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void anotherShopsUserIsNotFound() throws Exception {
        TestApi.Session other = api.registerShop("Other", "other@shop.test");
        change(other, staff.userId(), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void staffCannotManageUsers() throws Exception {
        change(staff, owner.userId(), "{\"status\":\"DISABLED\"}").andExpect(status().isForbidden());
    }

    @Test
    void theDemoLoginsAreProtectedButNewStaffAreNot() throws Exception {
        long demoShop = jdbc.queryForObject("INSERT INTO shop (name, is_demo) VALUES ('Demo', true) RETURNING id", Long.class);
        String hash = encoder.encode(TestApi.PASSWORD);
        jdbc.update("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'owner@demo.stockroom.dev', ?, 'OWNER')",
                demoShop, hash);
        long demoStaff = jdbc.queryForObject(
                "INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'staff@demo.stockroom.dev', ?, 'STAFF') RETURNING id",
                Long.class, demoShop, hash);
        TestApi.Session demoOwner = api.login("owner@demo.stockroom.dev", TestApi.PASSWORD);

        change(demoOwner, demoStaff, "{\"status\":\"DISABLED\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEMO_ACCOUNT_PROTECTED"));
        TestApi.Session visitorStaff = api.createStaff(demoOwner, "visitor@shop.test");
        change(demoOwner, visitorStaff.userId(), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
    }

    private ResultActions change(TestApi.Session caller, long userId, String body) throws Exception {
        return mvc.perform(patch("/api/users/" + userId).header("Authorization", caller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
```

Create `src/test/java/dev/wrenz/stockroom/concurrency/UserChangeConcurrencyTest.java`:

```java
package dev.wrenz.stockroom.concurrency;

import dev.wrenz.stockroom.common.ConflictException;
import dev.wrenz.stockroom.common.RefreshRejectedException;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.session.RefreshTokenService;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import dev.wrenz.stockroom.user.Role;
import dev.wrenz.stockroom.user.UpdateUserRequest;
import dev.wrenz.stockroom.user.UserService;
import dev.wrenz.stockroom.user.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec sections 4.3 and 4.5: user changes racing each other and racing a refresh. */
class UserChangeConcurrencyTest extends IntegrationTest {

    @Autowired UserService users;
    @Autowired RefreshTokenService sessions;

    ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(45, SECONDS)).as("worker threads still running").isTrue();
    }

    @Test
    void twoOwnersDemotingEachOtherLeaveExactlyOneOwner() throws Exception {
        TestApi.Session a = api.registerShop("Shop", "a@shop.test");
        TestApi.Session b = api.createStaff(a, "b@shop.test");
        AuthUser ownerA = new AuthUser(a.userId(), a.shopId(), Role.OWNER);
        AuthUser ownerB = new AuthUser(b.userId(), b.shopId(), Role.OWNER);
        users.update(ownerA, b.userId(), new UpdateUserRequest(Role.OWNER, null));

        CountDownLatch start = new CountDownLatch(1);
        Future<String> aDemotesB = pool.submit(() -> demote(start, ownerA, b.userId()));
        Future<String> bDemotesA = pool.submit(() -> demote(start, ownerB, a.userId()));
        start.countDown();

        assertThat(List.of(aDemotesB.get(30, SECONDS), bDemotesA.get(30, SECONDS)))
                .containsExactlyInAnyOrder("DONE", "LAST_OWNER");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE shop_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                Integer.class, a.shopId())).isEqualTo(1);
    }

    @Test
    void aSessionThatSurvivesARacingDisableIsEndedByReEnabling() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        AuthUser caller = new AuthUser(owner.userId(), owner.shopId(), Role.OWNER);

        CountDownLatch start = new CountDownLatch(1);
        Future<String> refresh = pool.submit(() -> {
            start.await();
            try {
                return sessions.rotate(staff.refreshToken()).refreshToken();
            } catch (RefreshRejectedException e) {
                return null;
            }
        });
        Future<Void> disable = pool.submit(() -> {
            start.await();
            users.update(caller, staff.userId(), new UpdateUserRequest(null, UserStatus.DISABLED));
            return null;
        });
        start.countDown();
        String successor = refresh.get(30, SECONDS);
        disable.get(30, SECONDS);

        users.update(caller, staff.userId(), new UpdateUserRequest(null, UserStatus.ACTIVE));

        assertThatThrownBy(() -> sessions.rotate(staff.refreshToken())).isInstanceOf(RefreshRejectedException.class);
        if (successor != null) {
            assertThatThrownBy(() -> sessions.rotate(successor)).isInstanceOf(RefreshRejectedException.class);
        }
    }

    private String demote(CountDownLatch start, AuthUser caller, long target) throws InterruptedException {
        start.await();
        try {
            users.update(caller, target, new UpdateUserRequest(Role.STAFF, null));
            return "DONE";
        } catch (ConflictException e) {
            return e.getCode();
        }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -B test -Dtest='UserManagementTest,UserChangeConcurrencyTest'`
Expected: compilation fails (`UpdateUserRequest` and `UserService.update` don't exist).

- [ ] **Step 3: Implement**

Create `src/main/java/dev/wrenz/stockroom/user/UpdateUserRequest.java`:

```java
package dev.wrenz.stockroom.user;

import jakarta.validation.constraints.AssertTrue;

/** PATCH /api/users/{id}: change the role, the status, or both (spec section 4.5). */
public record UpdateUserRequest(Role role, UserStatus status) {

    @AssertTrue(message = "Send a role, a status, or both")
    public boolean isAnyFieldPresent() {
        return role != null || status != null;
    }
}
```

In `AppUserRepository`, add (imports `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`):

```java
    Optional<AppUser> findByIdAndShopId(long id, long shopId);

    /** Locks this shop's active owners (SELECT ... FOR UPDATE), so two owners can't demote each other at once. */
    @Query(value = "SELECT id FROM app_user WHERE shop_id = :shopId AND role = 'OWNER' AND status = 'ACTIVE' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> lockActiveOwnerIds(@Param("shopId") long shopId);
```

In `ReservedEmails`, add:

```java
    /** Only DemoDataSeeder can create this domain, so in the demo shop these are the README's demo logins. */
    public static boolean isDemoAccount(String email) {
        return email.endsWith(DEMO_DOMAIN);
    }
```

In `UserService`:

1. Add a `private final RefreshTokenService sessions;` field as the fourth constructor parameter (`AppUserRepository users, ShopRepository shops, PasswordEncoder encoder, RefreshTokenService sessions`), with imports `dev.wrenz.stockroom.common.ConflictException`, `dev.wrenz.stockroom.session.RefreshTokenService`.
2. Add:

```java
    /**
     * Role and status changes (spec section 4.5). The shop's active owners are locked first; then the rules run in
     * order: not found, not yourself, demo logins protected, at least one active owner.
     */
    @Transactional
    public UserResponse update(AuthUser caller, long id, UpdateUserRequest request) {
        List<Long> activeOwnerIds = users.lockActiveOwnerIds(caller.shopId());
        AppUser target = users.findByIdAndShopId(id, caller.shopId())
                .orElseThrow(() -> new NotFoundException("User", id));
        if (target.getId() == caller.userId()) {
            throw new ConflictException("CANNOT_CHANGE_SELF", "You can't change or disable your own account");
        }
        if (ReservedEmails.isDemoAccount(target.getEmail()) && isDemoShop(caller.shopId())) {
            throw new ConflictException("DEMO_ACCOUNT_PROTECTED", "The demo logins can't be changed. Add a staff member to try this.");
        }
        Role role = request.role() != null ? request.role() : target.getRole();
        UserStatus status = request.status() != null ? request.status() : target.getStatus();
        boolean losesOwnerRights = activeOwnerIds.contains(target.getId()) && (role != Role.OWNER || status != UserStatus.ACTIVE);
        if (losesOwnerRights && activeOwnerIds.size() == 1) {
            throw new ConflictException("LAST_OWNER", "A shop needs at least one active owner");
        }
        boolean statusChanges = status != target.getStatus();
        target.changeRole(role);
        target.changeStatus(status);
        if (statusChanges) {
            // Disabling ends every session now; re-enabling also ends any that survived a racing refresh (spec 4.3).
            sessions.endAllSessions(target.getId());
        }
        return UserResponse.from(target);
    }

    private boolean isDemoShop(long shopId) {
        return shops.findById(shopId).map(Shop::isDemo).orElse(false);
    }
```

In `UserController`, add (imports `org.springframework.web.bind.annotation.PatchMapping`, `org.springframework.web.bind.annotation.PathVariable`):

```java
    /** Disable, re-enable, promote or demote. There is no DELETE: "remove" means disable (spec section 4.5). */
    @PatchMapping("/{id}")
    public UserResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return users.update(user, id, request);
    }
```

- [ ] **Step 4: Run the tests and the full suite**

```bash
./mvnw -B test -Dtest='UserManagementTest,UserChangeConcurrencyTest'
./mvnw -B verify
```

Expected: PASS, then `BUILD SUCCESS` with the previous count + 11.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/wrenz/stockroom/user src/test/java/dev/wrenz/stockroom/user/UserManagementTest.java \
  src/test/java/dev/wrenz/stockroom/concurrency/UserChangeConcurrencyTest.java
git commit -m "feat: owners disable, re-enable, promote and demote staff; locked last-owner rule; demo logins protected"
```

---

### Task 7: README, pull request, review, merge, live check

**Files:**
- Modify: `README.md`
- Modify (gitignored): `.superpowers/sdd/2026-10-12-phase2/progress.md`

- [ ] **Step 1: Update the README**

1. In the `## API` table, add these rows after the `/api/auth/register` row:

```markdown
| POST | `/api/auth/refresh`, `/api/auth/logout` | anyone holding the refresh cookie |
| GET | `/api/me` | owner, staff |
| PATCH | `/api/users/{id}` | owner |
```

2. Add this section directly before `## Known limits (deliberate for Phase 1)`:

```markdown
## Staying logged in, safely

- **Two tokens.** The access token (a JWT) lasts 15 minutes and is kept in memory by the front end. The refresh token sits in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/auth`, so page scripts can't read it and other sites can't send it. Only its SHA-256 hash is stored.
- **Rotation with reuse detection.** Each refresh token works once and is swapped for a new one, with the same guarded `UPDATE` that protects stock. If a used token ever comes back, it may have been stolen, so the whole session is revoked.
- **The database decides on every request.** `CurrentUserFilter` loads the caller's row after the token's signature is checked: a disabled user is refused at once, and the role comes from the row, not the token. Removing or demoting staff takes effect on their next request.
- **Owners manage staff** with `PATCH /api/users/{id}`: disable, re-enable, promote, demote. An owner can't change themselves, and the shop always keeps an active owner, enforced under a row lock.
```

3. In "Known limits", replace the line starting `- **Token claims are trusted for up to 1 hour.**` with:

```markdown
- **Two tabs refreshing at the same instant** send the same refresh token twice; reuse detection then ends that session and both tabs return to the login page. Coordinating tabs (for example with `BroadcastChannel`) is a follow-up.
```

4. In "Try it", after the line `- Call \`POST /api/auth/login\`, copy \`accessToken\`, click **Authorize**, and paste it.`, add:

```markdown
- Access tokens last 15 minutes; after that, log in again in Swagger (the browser app refreshes automatically).
```

5. In "What I'd do next", remove `a "remove staff" flow with per-request user checks, ` from the sentence.

- [ ] **Step 2: Final full run, commit and push**

```bash
./mvnw -B verify
git add README.md
git commit -m "docs: how sessions and the per-request check work; new endpoints; tab-race limit"
git push -u origin m1/auth-hardening
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Open the pull request**

Write the body to `C:/Users/OASIS/AppData/Local/Temp/m1-pr.md` with the Write tool (no `Co-Authored-By`, no "Generated with" line):

```markdown
Milestone M1 of Phase 2 (spec section 4): the API side of "stay logged in" and "remove staff".

- **Per-request check:** `CurrentUserFilter` loads the caller after the JWT is verified. Disabled, missing or moved users get 401; roles come from the database; a database error is a 500, never a 401. Replaces the Phase 1 "stale claims" limitation.
- **Refresh tokens:** `HttpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie, hashed at rest, rotated on every use, reuse revokes the session (committed despite the 401). Logout, a separate refresh rate limit, a `Sec-Fetch-Site` check, login housekeeping (50 sessions max).
- **Staff management:** `PATCH /api/users/{id}` (role/status). Not yourself, last-owner rule under a row lock, demo logins protected, disabling and re-enabling end all sessions.
- **Also:** `GET /api/me`, 15-minute access tokens, `WWW-Authenticate: Bearer` on 401s, V2 migration.
- **Race tests:** two refreshes of one token, logout vs refresh, disable vs refresh, two owners demoting each other.

Tests: <final count> passing.
```

```bash
gh pr create --repo WrenzLaylo/stockroom-api --base main --head m1/auth-hardening \
  --title "M1: refresh tokens, per-request user checks, staff management" --body-file C:/Users/OASIS/AppData/Local/Temp/m1-pr.md
```

- [ ] **Step 4: Code review on Fable 5.1 (spec section 10.2)**

Dispatch a fresh reviewer with `model: "fable"` on the PR diff (`gh pr diff <number> --repo WrenzLaylo/stockroom-api`), with spec section 4 as the requirement. Ask it to look hardest at: the refresh rotation and revocation order under READ COMMITTED, the `noRollbackFor` on `rotate`, `CurrentUserFilter`'s two failure paths, the last-owner lock, and anything that changed an existing test's meaning. Rules for it: read-only, never kill processes by name, report Blocking / Should change / Nice to have with file and line. Verify each finding against the code, fix accepted ones with a test where behaviour changes, push, and record every ruling in the ledger.

- [ ] **Step 5: Wrenz merges, then check the live app**

After Wrenz merges and the CD run on `main` succeeds (if M0 is merged), check the live API with a cookie jar:

```bash
BASE=https://$(gh variable get AZURE_WEBAPP_NAME --repo WrenzLaylo/stockroom-api).azurewebsites.net
JAR=/c/Users/OASIS/AppData/Local/Temp/stockroom-cookies.txt
curl -fsS -c $JAR -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@demo.stockroom.dev","password":"demo-owner-2026"}' -o /dev/null -w "login %{http_code}\n"
curl -fsS -b $JAR -c $JAR -X POST $BASE/api/auth/refresh -o /dev/null -w "refresh %{http_code}\n"
curl -fsS -b $JAR -X POST $BASE/api/auth/logout -o /dev/null -w "logout %{http_code}\n"
curl -s -b $JAR -X POST $BASE/api/auth/refresh -o /dev/null -w "refresh after logout %{http_code}\n"
rm -f $JAR
```

Expected: `login 200`, `refresh 200`, `logout 204`, `refresh after logout 401`.

- [ ] **Step 6: Close out the ledger**

Append to `.superpowers/sdd/2026-10-12-phase2/progress.md` under a `## M1: Authentication hardening` heading: the PR number, final test count, review rulings, merge commit, the live check output, and `M1: complete`.
