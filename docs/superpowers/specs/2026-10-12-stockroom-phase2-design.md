# Stockroom: Design Spec (Phase 2)

- **Date:** 2026-10-12
- **Status:** Revised after review 1 (section 13). Approved by the author 2026-10-12.
- **Author:** Wrenz Ivan M. Laylo (design drafted with Claude; architecture advice from Fable 5.1)
- **Builds on:** `docs/superpowers/specs/2026-09-28-stockroom-api-design.md` (Phase 1, "the Phase 1 spec"). Section numbers like "P1 §6" point there.

---

## 0. Brief for the reviewer

You are reviewing a design **before any Phase 2 code exists**. Be critical: fixing the design now is much cheaper than fixing code later.

**Background.** Phase 1 is finished: a multi-tenant inventory and orders REST API (Java 21, Spring Boot 4.1.1, Spring Security with JWT, JPA, Flyway, PostgreSQL 16), 113 tests, CI green, public on GitHub, not deployed. The author is a 2026 CS graduate applying for an Entry Level Cloud Developer role (Java / Spring Boot / **Angular** / AWS or Azure). Phase 2 adds the Angular front end and a free live deployment.

**Goals, in priority order:**

1. **Interview piece.** The author must be able to explain every file and make a live change in an interview. Every addition is code the author must learn, so features are only added for a reason.
2. **Product later.** It may later become a tool for small shops. Phase 2 must not block that, but builds none of it.

**Hard constraint: hosting costs $0 per month, indefinitely,** with no realistic path to a surprise bill (section 3).

**How the code will be written.** Claude writes the code; the author studies it with a study guide and makes one practice change alone. AI assistance is disclosed in the README.

**Please give your review in this format:**

- **Blocking issues:** anything wrong, unsafe, or embarrassing in a technical interview.
- **Should change:** better choices with a clear reason.
- **Scope:** anything to cut, or anything missing that an interviewer would expect.
- **Unverified facts:** section 12 lists claims not yet proven; say if any of them is likely wrong.

---

## 1. Summary

Phase 2 turns the API into a full-stack app with a live link:

- **Angular 22 front end**, served by the same Spring Boot app (one address).
- **Free hosting:** Azure App Service F1 for the app, Neon Free for PostgreSQL.
- **"Stay logged in":** refresh tokens with rotation and reuse detection, in an `HttpOnly` cookie.
- **Live user checks:** the server reads the user's current status and role from the database on every request, so removing or demoting staff takes effect immediately. This closes the Phase 1 "stale claims" limitation (P1 §6).
- **Staff management:** the owner can disable, re-enable, promote and demote staff.
- **Dashboard:** low stock, sales totals, top sellers and a 30-day chart, backed by new report endpoints.

The architecture stays one deployable service. The front end is a folder in the same repository (`web/`), built into the jar.

---

## 2. Scope

**In scope (Phase 2):**

- Deploying to App Service F1 + Neon Free, with CD from `main`
- Refresh tokens (rotation, reuse detection, logout), 15-minute access tokens
- Per-request user status and role lookup
- Disable, re-enable, promote and demote users; `GET /api/me`
- Report endpoints and the dashboard
- The Angular app: login, register, products, stock, orders, staff, dashboard
- Vitest unit tests, Playwright browser tests, CI for both
- README, architecture diagram and study guide updates

**Out of scope (Phase 2):**

- Password reset, email, "remember me" choice, multiple sessions UI
- Coordinating refreshes across browser tabs (section 4.3, known limit)
- Payments, customers, billing, subscriptions, barcode scanning, offline mode
- Setting a shop's time zone through the API (the column exists; only its default is used)
- A paid Azure database, Container Apps, or any paid SKU (section 3.5 says when Container Apps would be reconsidered)
- The Phase 1 review's deferred minor items, except the two in section 8.2 and 9.3

---

## 3. Hosting and deployment (milestone M0)

### 3.1 Why these services

Researched 2026-10-12 (sources in `.superpowers/sdd/2026-10-12-phase2/advisor-notes.md`):

- **No Azure PostgreSQL is free indefinitely.** Flexible Server B1ms is free for 12 months only, then about $19 per month.
- **Azure needs a card,** and after 30 days a pay-as-you-go subscription, which has **no hard spending cap**. $0 is still achievable by creating only SKUs that are metered at $0.
- **App Service F1** (Linux, Java 21) is metered at $0.00 per hour, so it cannot bill.
- **Neon Free** is permanent, needs no card, and when it hits its monthly limits it suspends until the next month instead of billing.
- **Rejected:** Azure for Students (needs current enrollment), AWS free plan (closes after 6 months), Google Cloud Run (needs a paid billing account after the trial), Supabase (pauses after a week idle), Render Postgres (expires after 30 days), Azure SQL free offer (SQL Server; the migrations are PostgreSQL-specific).

### 3.2 Runtime

- One jar on **App Service F1**, Linux, Java 21, Southeast Asia region. HTTPS Only is on.
- **Neon Free** project in `aws-ap-southeast-1` (Singapore). The app uses Neon's **direct** connection string, not the `-pooler` one, because Flyway needs a direct connection.
- Profiles on Azure: `prod,demo`. The `demo` profile runs the existing `DemoDataSeeder`.
- **F1 limits the design accepts:** 1 GB memory, 60 CPU-minutes per day, 165 MB of outbound traffic per day, no custom domain, the app is unloaded after 20 idle minutes, and if a daily quota runs out the app is stopped (403) until midnight UTC (8 AM in Manila). Hitting a quota stops the app; it never bills.

### 3.3 Production configuration (`application-prod.yml`)

- `stockroom.rate-limit.trust-forwarded-for: true` (P1 §6 already requires this behind Azure's front end).
- **`server.forward-headers-strategy: none`, set explicitly in `application.yml` for every profile, with a comment saying why.** Spring Boot detects Azure App Service (from its `WEBSITE_*` environment variables) and then defaults this property to `native`, which installs Tomcat's `RemoteIpValve`. The valve takes the rightmost address as the client and **rewrites `X-Forwarded-For` to the remaining left-hand entries**. A request sent with `X-Forwarded-For: fake` would reach the app as `fake` only, and `ClientIpResolver` (which trusts the last entry, P1 §6) would rate-limit the forged address: the per-IP login limit would be bypassable, on Azure only, with every local test still green. A real-server test starts the app as if on Azure (`spring.main.cloud-platform=azure_app_service`) and proves a forged header still cannot move the client IP.
- HikariCP `maximum-pool-size: 5`, `minimum-idle: 0`, `idle-timeout: 60s` and `connection-timeout: 10s`: a request that wakes Neon waits instead of failing, and the pool does not hold idle connections that could keep Neon's compute awake and spend its monthly compute hours.
- JDBC URL with `sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`: full certificate and host-name checks against the JVM's trust store (pgjdbc's default factory would look for a root certificate file instead). Verified in M0 (section 12); if it fails, the fallback is `sslmode=require` with the reason recorded.
- `server.compression.enabled: true`, and long-lived cache headers for Angular's content-hashed files, because F1 allows only 165 MB of outbound traffic per day (section 3.2).
- JVM options (App Service setting `JAVA_OPTS`): `-XX:MaxRAMPercentage=70 -XX:TieredStopAtLevel=1`, chosen for a 1 GB box and a faster start.
- **Swagger links behind TLS:** with forwarded headers off, the app sees plain HTTP behind Azure's TLS termination. `OpenApiConfig` therefore declares a relative server URL (`/`), so Swagger's "Try it out" calls use whatever scheme the page was loaded with.
- Secrets (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`) live in App Service settings, never in git.
- Neon's compute is capped at 0.25 CU (its smallest size), so the free 100 compute-hours a month last 400 hours of activity.

### 3.4 Continuous deployment

- A `deploy` job in the existing workflow runs on push to `main` only, after every other job passes.
- It signs in with `azure/login` using GitHub OIDC (a federated credential on an Entra app registration, no stored password) and deploys the jar with `azure/webapps-deploy`. If OIDC proves impractical on the account, the fallback is a publish profile stored as a GitHub secret.
- After deploying, a smoke step calls `GET /actuator/health` and expects `{"status":"UP"}`, allowing for a cold start.

### 3.5 Cost guardrails and the measure-then-decide rule

- **Guardrails:** a $1 budget with email alerts at 50% and 100%. Application Insights and Log Analytics are switched **off** when creating the app (the portal turns them on by default). After day 1, the author checks Cost Management shows $0.
- **Who runs what:** Claude writes every Azure CLI command into the plan; the author reviews them, runs `az login` himself, and nothing is created before he has seen the commands.
- **Measured on the live app (recorded in the README and the progress ledger):**
  1. Time to first response after 20+ idle minutes (cold start).
  2. Memory use and daily CPU-quota use.
  3. A forged `X-Forwarded-For` request proving F1 appends the real client IP as the **last** entry (the rate limiter depends on it).
- **Decision rule:**
  - Cold start over **15 seconds** → add the launcher page (section 5.8) on Azure Static Web Apps Free, also a $0 SKU that cannot bill.
  - The daily CPU quota trips, or a cold start exceeds **60 seconds** → reconsider Azure Container Apps, only with the author's approval and only after confirming that the "Environment Management Hour" meter (seen in Azure's price list from 2026-09-01) does not apply to a Consumption-only environment.

### 3.6 Honest claims

- README: the live link, demo logins, and a free-tier note stating the measured cold start (for example "the first load after the app has been idle takes about 25 seconds", with the real number).
- Resume, once live: "deployed on Azure App Service with Neon PostgreSQL". Never "Azure Database for PostgreSQL".

---

## 4. Authentication hardening (milestone M1, API only)

### 4.1 Two tokens

| | Access token | Refresh token |
|---|---|---|
| What | HS256 JWT, as in Phase 1 | 32 random bytes, base64url |
| Lifetime | **15 minutes** (`stockroom.jwt.ttl`, was 1h) | 14 days; a session can last 30 days at most (absolute, counted from login) |
| Where the client keeps it | Angular: memory only. Swagger: the Authorize dialog, as today | `HttpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie named `stockroom_refresh` |
| Where the server keeps it | Nowhere (stateless) | SHA-256 hash only, in `refresh_token` |

- Login and register return the access token in the body exactly as today (`TokenResponse`) **and** set the refresh cookie.
- `Secure` is always on, including in development. Chrome and Firefox accept `Secure` cookies on `http://localhost`; Safari does not. So local development uses Chrome or Firefox, and the Playwright tests run on Chromium.
- The access token keeps its claims (`sub`, `shopId`, `role`), but authority now comes from the database (section 4.4). The claims are still useful in logs and to detect a token from the wrong shop.

### 4.2 Data model: migration `V2__auth_sessions.sql`

```sql
ALTER TABLE app_user
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'));

CREATE TABLE refresh_token (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    family_id    UUID        NOT NULL,
    token_hash   CHAR(43)    NOT NULL CONSTRAINT uq_refresh_token_hash UNIQUE,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    family_expires_at TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id, shop_id) REFERENCES app_user (id, shop_id)
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
```

- The composite foreign key follows the Phase 1 tenant rule (every child references `(id, shop_id)`).
- `family_expires_at` is copied from the first token of a family to every successor, so the 30-day absolute limit needs no extra lookup.
- `AppUser` maps `status` in the same change, because `ddl-auto: validate` fails otherwise.
- `ops/reset-demo.sql` gains `DELETE FROM refresh_token WHERE shop_id = demo_shop_id;` before it deletes users. `IntegrationTest`'s truncate list gains `refresh_token`.

### 4.3 Refresh, rotation and logout

**`POST /api/auth/refresh`** (no body, no `Authorization` header; reads the cookie):

1. Missing cookie, unknown hash, expired (`expires_at` or `family_expires_at` passed), revoked, or the user is `DISABLED` → 401 `REFRESH_INVALID`, and the cookie is cleared.
2. Mark it used with a **conditional update**, the same idiom as the stock guard (P1 §5):
   `UPDATE refresh_token SET used_at = now() WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL`.
   One row updated means this request won.
3. Zero rows updated means another transaction changed the row after step 1 read it. Re-read it:
   - `used_at` is set: the token was **already used**, either a stolen token being replayed or two requests racing. Either way, revoke the whole family (`revoked_at = now()` on every row with that `family_id`), return 401 `REFRESH_REUSED`, clear the cookie.
   - only `revoked_at` is set (a logout or a disable got there first): return 401 `REFRESH_INVALID`, clear the cookie.
4. The winner inserts the successor token in the same family, sets the new cookie, and returns a fresh `TokenResponse` built from the user's **current** database role.

**`POST /api/auth/logout`:** revokes the cookie's family (if any), clears the cookie, returns 204. Always 204, even without a cookie.

**Races between refresh and revocation.** Family revocation (logout and step 3) runs as two statements in one transaction: first an `UPDATE` of the presented token's own row, which waits for any refresh of that token still in flight; then the `UPDATE ... WHERE family_id = ?`. Under PostgreSQL's default READ COMMITTED isolation the second statement sees rows committed after the first one waited, so a successor inserted by a racing refresh is revoked too. A concurrency test covers logout racing a refresh.

**Disable racing a refresh.** Disabling has no token row to lock first, so a successor inserted by a refresh that commits during the disable can survive it unrevoked. That token is useless while the user is `DISABLED` (step 1 rejects it), so the gap is closed at the other end: **re-enabling a user also revokes all of their outstanding refresh tokens**. A re-enabled user always logs in again. A test covers it.

**Housekeeping:** F1 unloads idle apps, so there is no `@Scheduled` cleanup. On every login, the user's expired and revoked rows are deleted, and if the user has more than 50 unrevoked families, the oldest are revoked. The demo owner is shared by every visitor, so this keeps its rows bounded.

**Known limit (documented in the README):** two tabs of the same browser that refresh in the same instant send the same token twice. Reuse detection then revokes the family and both tabs return to the login page. Coordinating tabs (for example with `BroadcastChannel`) is a follow-up.

### 4.4 Live checks on every request

- `app_user.status` is `ACTIVE` or `DISABLED`.
- A custom `Converter<Jwt, AbstractAuthenticationToken>` replaces the current role-claim converter. After the signature and expiry checks it does **one primary-key lookup** and:
  - rejects with 401 `UNAUTHORIZED` if the user is missing, `DISABLED`, or belongs to a different shop than the token's `shopId` claim;
  - builds `ROLE_OWNER` / `ROLE_STAFF` from the **database** role, not the claim;
  - sets the principal to `AuthUser(userId, shopId, role)` loaded from the database.
- **How a rejection becomes a 401:** Spring Security only sends `AuthenticationException`s to the entry point that writes the 401 `ProblemDetail` (P1 §6). The converter therefore throws an `AuthenticationException` subclass (for example `InvalidBearerTokenException` or `DisabledException`), **not** the app's own `UnauthorizedException`, which is an ordinary `RuntimeException` and would surface as a 500.
- **A database failure is not a 401.** If the lookup itself fails (for example Neon is unreachable), the response is 500 `INTERNAL_ERROR` in the standard shape, never 401: a 401 would make the Angular interceptor log every user out because of a database hiccup. A test simulates a failing lookup and asserts the 500 and its shape.
- The lookup runs in the security filter, outside any transaction (`open-in-view` is off), so it borrows a connection briefly and returns it before the controller's own work starts. With a pool of 5 that is fine.
- **No cache.** A cache would bring back the stale window this feature removes. The cost is one indexed read per request on endpoints that already use the database.
- Controllers change from `AuthUser.from(jwt)` to `@AuthenticationPrincipal AuthUser`. `UserIdRequestAttributeFilter` must recognise the new authentication type, or request logs silently lose `user=`. A test asserts the log attribute is still set.
- This replaces the Phase 1 "stale claims" limitation (P1 §6); the README section about it is rewritten.

### 4.5 Managing users

| Endpoint | Who | Body | Effect |
|---|---|---|---|
| `GET /api/me` | owner, staff | | Current user from the database: `id, email, role, status, shopId, shopName` |
| `GET /api/users` (exists) | owner | | Now includes `status` |
| `PATCH /api/users/{id}` | owner | `{ "role"?: "OWNER"\|"STAFF", "status"?: "ACTIVE"\|"DISABLED" }` | Changes role and/or status; returns the updated user |

There is deliberately no `DELETE`: "remove" is `PATCH {status: DISABLED}`, and one route per effect is easier to explain.

`PATCH` details:

- An empty body (neither field) → 400 `VALIDATION_FAILED`.
- Setting a value the user already has is a no-op that returns 200 (idempotent).
- A disabled user's role may be changed. Only `ACTIVE` owners count for the last-owner rule.

Rules, checked in one service method inside a transaction:

- **Not yourself:** an owner cannot change or disable their own account → 409 `CANNOT_CHANGE_SELF`.
- **Always one active owner:** a change that would leave the shop with no `ACTIVE` `OWNER` → 409 `LAST_OWNER`. The owner rows of the shop are locked (`SELECT ... FOR UPDATE`) before counting, so two owners cannot demote each other at the same moment.
- **Disabling revokes** all of that user's refresh tokens in the same transaction. Their access token stops working on its next request (section 4.4).
- **Hard delete is impossible** by design: orders and stock movements reference the user who created them. "Remove" means disable, and re-enable undoes it, which matters because a disabled user's email stays taken.
- **Demo accounts are protected:** in the shop flagged `is_demo`, the two seeded accounts (`owner@demo…`, `staff@demo…`) cannot be disabled or have their role changed → 409 `DEMO_ACCOUNT_PROTECTED`. Otherwise one visitor could break the README's demo logins for everyone. Visitors can still add staff and then remove them, so the feature is fully demonstrable.
- A user from another shop returns 404 (P1 §6 rule).
- A disabled user's login gets the same generic 401 as a wrong password (no account status is revealed).

### 4.6 Forged-request protection and rate limits

- The refresh cookie makes `/api/auth/refresh` and `/api/auth/logout` cookie-authenticated, so the Phase 1 comment "CSRF disabled because there are no cookies" becomes false and is rewritten.
- `SameSite=Strict` already stops other sites from sending the cookie. As a second check, both endpoints reject a request whose `Sec-Fetch-Site` header is present and is anything other than `same-origin` → 403 `FORBIDDEN`. Current Chrome, Edge, Firefox and Safari send this header on every request. Requests without it (curl, other non-browser clients) are allowed: a forged cross-site request needs a browser that sends the victim's cookie automatically, and a browser too old to send `Sec-Fetch-Site` still honours `SameSite=Strict`. The rule needs no configured origin, so it works the same in production, behind the `ng serve` proxy, on a bare local jar, in Playwright and in Swagger. Spring's CSRF tokens stay off; the Bearer endpoints are not cookie-authenticated and need none.
- The existing `BearerTokenResolver` already ignores `Authorization` on `/api/auth/**`, which fits: refresh and logout are cookie-only and `permitAll`.
- **Rate limit:** refresh gets its own per-IP counter (60 per 15 minutes, same Caffeine design as P1 §6), so normal use never eats into the login budget. Login and register keep their Phase 1 limits.

### 4.7 Tests (M1)

- Login and register set the cookie with `HttpOnly`, `Secure`, `SameSite=Strict` and `Path=/api/auth` (asserted explicitly; MockMvc does not enforce flags).
- Refresh rotates: the old token stops working, the new one works.
- Replaying a used token revokes the family: the successor stops working too.
- Two concurrent refreshes with the same token: exactly one succeeds (same concurrency test style as Phase 1).
- Expired, family-expired, revoked and unknown tokens → `REFRESH_INVALID`.
- Logout revokes and clears; logout without a cookie → 204.
- Logout racing a refresh of the same token: afterwards, no token in the family works.
- `Sec-Fetch-Site: cross-site` and `same-site` → 403; `same-origin` and a missing header → allowed.
- A disabled user's access token is rejected on its next request; their refresh fails.
- A demoted owner gets 403 on owner endpoints on the next request.
- `LAST_OWNER`, `CANNOT_CHANGE_SELF`, `DEMO_ACCOUNT_PROTECTED`, cross-shop 404 on `PATCH`, empty `PATCH` body → 400, and a repeated `PATCH` → 200 no-op.
- A refresh racing a disable: after re-enabling, no token issued before the disable works.
- A failing user lookup in the security filter → 500 `INTERNAL_ERROR` in the standard shape, not 401.
- The app started as if on Azure (`spring.main.cloud-platform=azure_app_service`, real server) still ignores a forged `X-Forwarded-For` entry (section 3.3).
- Two owners demoting each other concurrently: one succeeds, one gets `LAST_OWNER`.
- Request logs still include the user ID.
- 401 responses from Bearer-protected endpoints include `WWW-Authenticate: Bearer` (section 8.2).

---

## 5. The Angular app (milestones M2 to M4)

### 5.1 Stack

- **Angular 22** (latest 22.x patch at scaffold time, pinned in `package-lock.json`): standalone components, signals, zoneless change detection (the default since v21).
- Reads use `httpResource`; writes use `HttpClient`.
- **State:** small feature services holding signals, plus one `AuthStore`. No NgRx: nothing to learn beyond signals.
- **UI kit:** Angular Material 22 with a custom Material 3 theme (applied in the design pass, section 10.3).
- **Package manager:** npm. `package-lock.json` is committed and CI installs with `npm ci`. The Node version is pinned to an LTS release supported by Angular 22 in two places: `web/.nvmrc` (local development, and CI through `setup-node`'s `node-version-file`) and the pom's `<nodeVersion>` (the Maven build). A CI step fails if the two differ.

### 5.2 Structure (`web/src/app/`)

- `core/`: `AuthStore`, the HTTP interceptor, route guards, and one error mapper (section 8.3).
- `features/auth`, `features/products`, `features/orders`, `features/staff`, `features/dashboard`: one folder per area, each with its routes, pages and service.
- `shared/`: a few reusable pieces (page header, confirm dialog, money and date formatting).

### 5.3 Login flow

- `AuthStore` holds the access token, its expiry, and the current user from `GET /api/me`, all in memory.
- **Interceptor:** attaches `Authorization: Bearer …` to API requests. On a 401 from any non-`/api/auth/*` URL it calls `AuthStore.refresh()`. `refresh()` keeps **one in-flight request** and hands the same result to every concurrent caller; each failed request is retried once with the new token. If the refresh answers 401 or 429, the store is cleared and the router goes to `/login?returnUrl=<current path>` (a 429 also shows "Too many attempts, try again in a few minutes"). If the refresh fails with a network error or a 5xx, the session is kept and the request shows the Retry message (section 8.3): a server hiccup never logs anyone out.
- **On page load:** an app initializer calls `refresh()` once. If the cookie is valid, the session is restored without a login screen.
- **Guards:** an `authGuard` for signed-in routes and an `ownerGuard` for owner-only routes. They only hide UI; the server remains the gatekeeper.

### 5.4 Screens

| Screen | Who | What it does |
|---|---|---|
| Log in | everyone | Email and password; shows `RATE_LIMITED` as "Too many attempts, try again in a few minutes" |
| Register | everyone | Creates a shop and its owner, then signs in |
| App shell | signed in | Top bar with shop name, user and role; navigation to Dashboard, Products, Orders, Staff (owner only); log out |
| Products | owner, staff | Search, low-stock filter, paging. Owner: add and edit (dialog), archive, restock (dialog), stock history panel |
| New order | owner, staff | Pick products and quantities, see the total, place it. `INSUFFICIENT_STOCK` flags the exact line with the available quantity |
| Orders | owner, staff | Paged list and detail. Owner: cancel (with confirmation) |
| Staff | owner | List with role and status; add staff; change role; disable and re-enable (with confirmation). Protected demo accounts show why their actions are unavailable |
| Dashboard | owner, staff | Section 6.4 |

Every screen works down to 390 px wide.

### 5.5 Served from the jar

- `web/` builds into the jar's `static/` folder through a Maven profile `-Pweb` (`frontend-maven-plugin`, which downloads the pinned Node and runs `npm ci` and `ng build`). The profile is **off by default**, so `./mvnw test` stays fast and Java-only.
- **SPA fallback:** a controller forwards unknown `GET` paths that accept HTML to `index.html`. It must never catch `/api/**`, `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**` or `/error`. Static files and the fallback are `permitAll` in `SecurityConfig`.
- **Development:** `ng serve` proxies `/api` to `http://localhost:8080`, so development also uses one address and the cookie behaves as in production.

### 5.6 Tests

- **Vitest** (the Angular CLI default): the interceptor (single-flight refresh, retry once, redirect on failure), guards, `AuthStore`, the error mapper, and the order form.
- **Playwright** browser tests on Chromium (section 4.1) against the real jar and PostgreSQL:
  1. Log in, reload the page, still signed in.
  2. Place an order and see the product's stock drop.
  3. The owner disables a staff member; that staff member's open session is sent to the login page on its next action.
  4. Staff cannot reach the Staff page.

### 5.7 Error and slow-request behaviour

Section 8.3 maps errors. In addition, when an API request has been waiting for more than 3 seconds, the app shows "The free server is waking up…". This covers a tab left open past F1's 20-minute idle unload. It cannot cover the very first visit, because then the page itself is served by the sleeping app (section 5.8).

### 5.8 Optional launcher page (only if section 3.5 triggers it)

One static HTML file on Azure Static Web Apps Free: it shows "Starting the free-tier server…", polls the app's `/actuator/health` with an opaque `no-cors` request until it answers, then redirects to the app. It holds no tokens and calls no other API, so it does not reintroduce the cross-site cookie problem (section 11, decision D1).

---

## 6. Dashboard (milestone M5)

### 6.1 Data model: migration `V3__shop_timezone.sql`

```sql
ALTER TABLE shop ADD COLUMN timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Manila';
```

No API writes it in Phase 2. It exists so "today" means today in the shop's time zone and the reports can be tested at day boundaries.

### 6.2 Endpoints (all read-only, `JdbcTemplate` in one `ReportRepository`)

| Endpoint | Who | Returns |
|---|---|---|
| `GET /api/reports/stock` | owner, staff | `{ activeProducts, lowStockCount }` |
| `GET /api/reports/sales` | owner | `{ timezone, today, last7Days, last30Days }`, each `{ orders, revenue }` |
| `GET /api/reports/sales-by-day?from&to` | owner | `[{ date, orders, revenue }]`, one row per day, missing days filled with zeros |
| `GET /api/reports/top-products?days&limit` | owner, staff | `[{ productId, sku, name, quantity }]` |

The low-stock **list** reuses the existing `GET /api/products?lowStock=true`.

### 6.3 Rules

- **Sales** count only `PLACED` orders; `CANCELLED` orders are excluded from counts and revenue. Revenue is the sum of `customer_order.total`.
- **Days** are calendar days in the shop's time zone: an order belongs to `(created_at AT TIME ZONE shop.timezone)::date`.
- `today` is the current date in the shop's time zone. `last7Days` is today plus the 6 days before; `last30Days` is today plus the 29 days before.
- `sales-by-day`: `from` and `to` are ISO dates, inclusive, defaulting to the last 30 days. More than 92 days, or `from` after `to` → 400 `VALIDATION_FAILED`.
- `top-products`: total quantity sold in `PLACED` orders over the last `days` days (1 to 92, default 30), `limit` 1 to 20 (default 5), ties broken by name. Quantities only, no revenue, so staff may see it.
- Archived products still appear in `top-products` (they were sold), and are excluded from `activeProducts` and `lowStockCount`.
- **Queries filter on raw `created_at` bounds so the index is used:** `WHERE shop_id = ? AND created_at >= (:from::timestamp AT TIME ZONE :tz) AND created_at < ((:to + 1)::timestamp AT TIME ZONE :tz)`, and only the `GROUP BY` uses the date expression. Filtering on `(created_at AT TIME ZONE tz)::date` would be an expression the index `ix_order_shop_created (shop_id, created_at DESC)` cannot serve.

### 6.4 The page

- **Owner:** sales tiles for today, 7 days and 30 days; a 30-day sales bar chart; top sellers; the low-stock list linking to restock.
- **Staff:** the low-stock list, product counts and top sellers; no sales totals or revenue. (Staff already see prices and individual order totals in Phase 1; only the aggregates are owner-only.)
- **Chart:** a hand-written inline-SVG bar chart component (no chart library), with an accessible text summary and a value on hover or focus.

### 6.5 Demo data

`DemoDataSeeder` adds about 30 days of orders to the demo shop, including a few cancellations:

- Orders are created **through the normal order service**, so stock and the stock ledger stay consistent (P1 §5). The seed is one transaction, so a single `INSUFFICIENT_STOCK` would roll back everything and stop the app. The opening stock levels are therefore raised so that the planned sales fit, and chosen so that two products still end **below** their low-stock threshold, which keeps the low-stock panel and the restock flow demonstrable.
- After the orders exist, timestamps are moved back with `JdbcTemplate` so the history reads in order: product `created_at` and the `OPENING_STOCK` movements go back about 31 days; each order's `created_at`, `cancelled_at`, and its `SALE` and `CANCEL` movements go back to that order's planned day.
- `DemoDataSeederTest`'s expected counts are updated.
- **The live demo does not re-seed by itself:** the seeder skips an existing demo shop. M5's deployment therefore includes an explicit step: run `ops/reset-demo.sql` against Neon, then restart the app.

### 6.6 Tests (M5)

- An order at `2026-10-12T17:00Z` (1:00 AM on 13 October in Manila) counts on 13 October, not on its UTC date of 12 October. (A late-evening Manila order would not catch the bug: 11:30 PM in Manila is still the same date in UTC.)
- Cancelled orders are excluded from counts and revenue.
- Zero-filled days, the inclusive range, the 92-day cap, and `from` after `to`.
- Staff get 403 on `/sales` and `/sales-by-day`, and 200 on `/stock` and `/top-products`.
- Shop isolation: another shop's orders never appear in any report.

---

## 7. API changes at a glance

| Change | Endpoint | Milestone |
|---|---|---|
| New | `POST /api/auth/refresh`, `POST /api/auth/logout` | M1 |
| Changed | `POST /api/auth/login`, `POST /api/auth/register` also set the refresh cookie | M1 |
| New | `GET /api/me` | M1 |
| Changed | `GET /api/users` adds `status` | M1 |
| New | `PATCH /api/users/{id}` (role and status) | M1 |
| New | `GET /api/reports/stock`, `/sales`, `/sales-by-day`, `/top-products` | M5 |
| Changed | Access tokens last 15 minutes instead of 1 hour | M1 |

Nothing is removed, and every Phase 1 test keeps passing (updated only where the token lifetime or principal type changes).

---

## 8. Errors

### 8.1 New error codes (same `ProblemDetail` shape as P1 §7)

| HTTP | `code` | Trigger |
|---|---|---|
| 401 | `REFRESH_INVALID` | Refresh cookie missing, unknown, expired, revoked, or the user is disabled |
| 401 | `REFRESH_REUSED` | An already-used refresh token was presented; its family is now revoked |
| 409 | `CANNOT_CHANGE_SELF` | An owner tried to change or disable their own account |
| 409 | `LAST_OWNER` | The change would leave the shop with no active owner |
| 409 | `DEMO_ACCOUNT_PROTECTED` | A seeded demo account cannot be disabled or have its role changed |

### 8.2 Folded-in Phase 1 review item

401 responses from Bearer-protected endpoints include `WWW-Authenticate: Bearer` (RFC 6750), since M1 rewrites that code path anyway.

### 8.3 How Angular shows errors (one mapper in `core/`)

| Response | What the user sees |
|---|---|
| 401 | Refresh the session and retry once; if that fails, the login page |
| 403 | "You don't have access to this" |
| 404 | "Not found. It may have been archived or removed" |
| 409 `INSUFFICIENT_STOCK` | The exact order line, with the available quantity |
| Other 409 codes | A plain sentence per code |
| 400 with `errors[]` | Each message on its form field |
| 429 | "Too many attempts, try again in a few minutes" |
| Network error or 5xx | A message with a Retry button |

---

## 9. Testing and CI/CD

### 9.1 Test layers

- **Java:** the 113 Phase 1 tests plus the M1 and M5 tests above, same style (MockMvc, real PostgreSQL, concurrency tests with threads and latches).
- **Angular unit:** Vitest (section 5.6).
- **Browser:** Playwright (section 5.6), run against the jar built with `-Pweb`, using the `demo` profile's seeded accounts.

### 9.2 CI jobs (GitHub Actions)

1. `verify` (exists): Java tests against the PostgreSQL service container.
2. `web` (new): `npm ci`, Vitest, `ng build`.
3. `e2e` (new, needs 1 and 2): build the jar with `-Pweb`, start it against the PostgreSQL service with the `demo` profile, run Playwright, upload the trace on failure.
4. `deploy` (new, `main` only): section 3.4. It needs every other job: only `verify` in M0, and `web` and `e2e` too once M2 adds them.

### 9.3 Folded-in Phase 1 review item

CI runs once per change: `push` triggers only on `main`, and pull requests use `pull_request` (today both fire on PR branches).

---

## 10. Milestones, documentation and process

### 10.1 Milestones (each a branch and a PR series; CI green; the author merges)

| | Milestone | Ships |
|---|---|---|
| M0 | Deploy | F1 + Neon, `application-prod.yml`, CD, live link, measurements, launcher decision |
| M1 | Auth hardening | `V2`, refresh/logout, live checks, user management, `/api/me` |
| M2 | Angular skeleton | `web/`, login/register/logout, refresh, guards, shell, served from the jar, Vitest + first Playwright test, `web` and `e2e` CI jobs |
| M3 | Products and stock UI | Products list, add/edit, archive, restock, history |
| M4 | Orders and staff UI | New order, orders list/detail/cancel, staff management |
| M5 | Dashboard | `V3`, report endpoints, demo orders, dashboard page |
| M6 | Polish | Design pass, README, architecture diagram, study guide, practice change, repo rename decision |

M0 comes first so every later milestone ships to a live URL and the measurements settle the launcher question early.

### 10.2 Reviews

- Every milestone gets a code review by Fable 5.1 before the author merges.
- Visual milestones (M3 to M6) also get a Fable design critique of real screenshots at 390 px and 1440 px, sorted into must-fix and nice-to-have.

### 10.3 Design

UI work uses the design-taste skill. The Material theme, typography and colour are decided in M2's shell and refined in M6, then held consistent.

### 10.4 Documentation

- **README:** live link and demo logins, the free-tier note, an updated architecture diagram (browser → Spring Boot on App Service F1 → Neon), the refresh-token and live-check story, the tab-race known limit, and the AI-assistance disclosure kept. "What I'd do next" gains security headers: the app sees plain HTTP behind Azure's TLS termination (section 3.3), so Spring Security does not send HSTS, and the single-page app has no Content-Security-Policy yet.
- **Study guide:** new chapters on the refresh flow, the per-request check, the Angular structure and the interceptor; and one practice change the author completes alone.
- **Repository rename** (`stockroom-api` → `stockroom`): decided in M6. GitHub redirects the old URL, so links already sent keep working.

### 10.5 Progress ledger

`.superpowers/sdd/2026-10-12-phase2/progress.md` records rulings, decisions and test evidence per milestone, as in Phase 1.

---

## 11. Decisions and alternatives considered

| # | Decision | Chosen | Rejected and why |
|---|---|---|---|
| D1 | Where the front end lives | Served by the Spring Boot app (one address) | Static Web Apps + API on F1: the refresh cookie becomes a third-party cookie (`azurewebsites.net` is on the Public Suffix List), which Safari blocks and Chrome blocks in Incognito; keeping the token in JS storage instead is the least secure browser pattern in the IETF browser-apps guidance. A Static Web Apps proxy: an extra Node hop, a 45-second limit shorter than a cold start, and it breaks the rate limiter's IP rule |
| D2 | Hosting | App Service F1 + Neon Free | Container Apps (small bill risk, unverified meter; kept as the fallback in 3.5); Azure PostgreSQL (not free after 12 months); others in 3.1 |
| D3 | Refresh token storage | `HttpOnly; Secure; SameSite=Strict` cookie, hash in the database | Local storage (readable by any injected script) |
| D4 | Reuse response | Revoke the whole family | Revoke only the presented token (weaker; RFC 9700 requires rotation or sender-constraining, and family revocation is the common stricter variant) |
| D5 | Live check | One primary-key lookup per request, no cache | `token_version` claim (still needs the row for the new role); a cache (brings back the stale window) |
| D6 | Remove staff | Disable (soft) with re-enable | Hard delete (orders and movements reference users) |
| D7 | Forged requests | `SameSite=Strict` + `Sec-Fetch-Site` check on two endpoints | Spring CSRF tokens (more moving parts for the same protection here); an `Origin` allow-list (needs a configured origin per environment and broke local runs in review) |
| D8 | Angular state | Signals in feature services | NgRx (more to learn, no benefit at this size) |
| D9 | UI kit | Angular Material | Tailwind with hand-built components (more code to own, accessibility on us) |
| D10 | Package manager | npm | pnpm (its strengths are monorepos and disk use), Yarn (no benefit here) |
| D11 | Chart | Hand-written SVG | A chart library (a dependency for one chart) |
| D12 | Repository layout | Same repo, `web/` folder | Separate repo (two CIs, cross-repo PRs for auth changes) |
| D13 | Report day boundaries | Shop time zone column, server-side | Client-supplied time zone (harder to test, can disagree between users) |

---

## 12. Facts to verify during the build

These were researched but not proven on this project. Each is checked in the milestone named, and the result goes in the progress ledger.

| Fact | Check | Milestone |
|---|---|---|
| F1 appends the real client IP as the **last** `X-Forwarded-For` entry, and the explicit `forward-headers-strategy: none` holds on Azure | Forged-header request against the live app: the forged value must not be the limited key | M0 |
| Spring Boot cold start on F1 is acceptable | Measured (section 3.5) | M0 |
| Swagger works over HTTPS with the relative server URL | Use "Try it out" on the live app | M0 |
| `sslmode=verify-full` with `DefaultJavaSSLFactory` connects to Neon (its certificate chains to a CA in the JVM trust store) | First deploy | M0 |
| Neon's direct connection works with Flyway and survives Neon suspending | Deploy, idle past Neon's 5-minute suspend, call the API | M0 |
| With `minimum-idle: 0`, Neon still suspends while F1 keeps the app loaded (Neon's docs don't say whether idle connections block suspend) | Neon console after 10 idle minutes | M0 |
| GitHub OIDC login to Azure works on a free/pay-as-you-go personal account | First CD run | M0 |
| Angular 22 + Vitest is the CLI default and works with zoneless | Scaffold | M2 |
| `frontend-maven-plugin` runs the pinned Node and `npm ci` on GitHub's Ubuntu runner | First `-Pweb` CI run | M2 |

---

## 13. Review log

**Review 1: Fable 5.1, fresh reviewer, 2026-10-12. Verdict: "Ready with fixes".** Every finding was checked against the code before it was accepted.

| # | Finding | Resolution |
|---|---|---|
| B1 | On Azure, Spring Boot defaults `forward-headers-strategy` to `native`; Tomcat's `RemoteIpValve` rewrites `X-Forwarded-For`, so `ClientIpResolver` (verified: trusts the last entry) would rate-limit a forged address | Accepted. Explicit `none` in `application.yml`, a real-server test as if on Azure, live check in M0 (3.3, 4.7, 12) |
| B2 | The time-zone test used 11:30 PM Manila, which is the same date in UTC, so it could not fail | Accepted. Uses 1:00 AM Manila (6.6) |
| S1 | `Origin` check with a `localhost:4200` default would 403 Playwright, local `-Pweb` runs and Swagger, because browsers send `Origin` on same-origin POSTs | Accepted, with a simpler fix than suggested: dropped the `Origin` rule and the property; `Sec-Fetch-Site` must be `same-origin` when present (4.6, D7) |
| S2 | Converter exceptions reach the 401 entry point only if they are `AuthenticationException`s; `UnauthorizedException` (verified: a `RuntimeException`) would give a 500. A DB failure must not become a 401 | Accepted (4.4, 4.7) |
| S3 | A disable racing a refresh can leave a successor token unrevoked; zero updated rows can also mean a concurrent logout | Accepted. Re-enable revokes all tokens; zero rows are re-read to choose `REFRESH_REUSED` or `REFRESH_INVALID` (4.3) |
| S4 | Seeding 30 days of orders through the order service would hit `INSUFFICIENT_STOCK` with today's demo stock (verified: e.g. shampoo 3, oil 25) and roll back the seed | Accepted. Raised opening stock, two items end low, full backdating, explicit reset step (6.5) |
| S5 | Hikari keeps idle connections that may keep Neon awake; F1 also caps outbound traffic at 165 MB/day | Accepted. `minimum-idle: 0`, 0.25 CU cap, compression and caching (3.2, 3.3, 12) |
| S6 | Bucketing in the `WHERE` clause defeats the order index | Accepted. Raw `created_at` bounds (6.3) |
| S7 | Safari rejects `Secure` cookies on `http://localhost` | Accepted. Chromium for Playwright; Chrome or Firefox for development (4.1) |
| S8 | Ambiguities: empty `PATCH`, idempotent `PATCH`, role change while disabled, refresh 429 in the interceptor, "no money" for staff, Node pinned "in one place" | Accepted, each pinned (4.5, 5.1, 5.3, 6.4) |
| Scope | `DELETE /api/users/{id}` duplicates `PATCH {status: DISABLED}` | Accepted. Removed (4.5, 7) |
| Scope | No HSTS behind TLS termination | Accepted as a README "what I'd do next" item (10.4) |
| Unverified | `sslmode=verify-full` with pgjdbc's default factory needs a root certificate file | Accepted. `DefaultJavaSSLFactory`, verified in M0 (3.3, 12) |
