# Stockroom API: Design Spec (Phase 1)

- **Date:** 2026-09-28
- **Status:** Revised after review 1 (see section 10); awaiting the author's approval
- **Author:** Wrenz Ivan M. Laylo (design drafted with Claude)
- **Reviewer:** GPT 6 Astra

---

## 0. Brief for the reviewer

You are reviewing a design **before any code exists**. Please be critical: it is much cheaper to fix the design now than after implementation.

**Background.** The author is a 2026 BS Computer Science graduate. Their production work so far is TypeScript, Python and PHP. They are applying for an **Entry Level Cloud Developer** role whose stack is **Java / Spring Boot, Angular, and AWS/Azure**. Their GitHub currently has no Java. This project exists to fix that honestly: a small, real Spring Boot service that they can deploy, demo, and explain in an interview.

**Two goals, in priority order:**

1. **Interview piece (Phase 1, this spec).** Show Java OOP, Spring Boot, Spring Security, JPA, transactions, testing, CI and Azure deployment. The design must be small enough that the author can explain every file and make a live change during an interview.
2. **Product later (Phase 2, out of scope here).** It may later become a paid inventory tool for small shops (for example sari-sari stores in the Philippines), with an Angular front end. Phase 1 should not block that. In particular, multi-tenancy (one database, many shops) is designed in from the start.

**How the code will be written.** Claude writes the implementation. The author then studies it file by file, with a study guide and a practice change they must complete alone. The target employer explicitly welcomes AI coding assistants when their output is validated, so AI assistance will be disclosed in commits.

**Please give your review in this format:**

- **Blocking issues:** anything wrong or unsafe, or anything that would embarrass the author in a technical interview.
- **Should change:** better choices with a clear reason.
- **Scope:** anything that should be cut to keep this small, or anything missing that an interviewer would expect.
- **For a follow-up review:** section 10 lists what changed after review 1 and why; please check the resolutions, especially S3 (documented rather than changed).

Please don't suggest adding features "because they're nice". Every addition is code the author must learn.

---

## 1. Summary

**Stockroom API** is a multi-tenant REST API for small shops to manage products, stock and orders.

The core promise:

> Stock can never go negative, even when two orders arrive at the same moment, and each shop's data is isolated from every other shop.

**Stack:**

- Java 21
- Spring Boot, **pinned** to the latest stable release on the day the project is scaffolded, together with a springdoc-openapi version that supports it. Both are recorded in `pom.xml` and the README, and upgrades are deliberate changes after that. Also Spring Boot Actuator (health check only), Spring Web, Spring Data JPA, Spring Security (OAuth2 resource server for JWT), Bean Validation
- Flyway, PostgreSQL 16
- springdoc-openapi (Swagger UI)
- JUnit 5, AssertJ, MockMvc, JaCoCo
- Maven wrapper
- GitHub Actions
- Azure App Service (Linux, Java 21) and Azure Database for PostgreSQL Flexible Server

**Architecture:** one deployable service with the classic layers: controller → service → repository → database. There are no microservices, on purpose (see section 9).

---

## 2. Scope

**In scope (Phase 1):**

- Shop registration with an owner account, and login returning a JWT
- Owner-managed staff accounts; roles are `OWNER` and `STAFF`
- Product CRUD: archive instead of hard delete, a SKU unique per shop, and a low-stock filter
- Restock, plus a stock movement history (an append-only ledger)
- Placing orders atomically, and cancelling orders with the stock returned
- Consistent error responses (RFC 9457 `ProblemDetail`)
- Tests, CI, Azure deployment, a demo shop with demo logins, a README, and a study guide

**Out of scope (Phase 1):**

- Any front end: Swagger UI is the demo surface, and Angular comes in Phase 2
- Refresh tokens, password reset and email
- Payments, customers, discounts, taxes, multiple warehouses and barcode scanning
- Billing and subscriptions for selling the product
- Distributed rate limiting (the Phase 1 rate limit is in-memory, single instance)

---

## 3. Data model

All tables are created by Flyway migrations (`V1__init.sql` and so on). IDs are `BIGINT` identity columns, and timestamps are `TIMESTAMPTZ` stored in UTC.

| Table | Columns | Constraints and notes |
|---|---|---|
| `shop` | id, name, created_at | `name` is not null, 1–120 characters |
| `app_user` | id, shop_id → shop, email, password_hash, role, created_at | `email` is unique globally (case-insensitive, stored lowercase). **Product constraint: one email belongs to exactly one shop.** Changing that later changes how login identifies people, so it is a deliberate decision. `role` is `OWNER` or `STAFF`. Passwords are stored as BCrypt hashes only. `UNIQUE (id, shop_id)` (see "Tenant-aware foreign keys" below). |
| `product` | id, shop_id → shop, sku, name, price, stock_qty, low_stock_threshold, active, created_at, updated_at | `UNIQUE (shop_id, sku)` and `UNIQUE (id, shop_id)`. `price` is `NUMERIC(12,2)` with `CHECK (price >= 0)`. `stock_qty` has `CHECK (stock_qty >= 0)`. `low_stock_threshold` has `CHECK (>= 0)` and defaults to 5. `active` defaults to true. |
| `customer_order` | id, shop_id → shop, status, total, created_by, created_at, cancelled_at | `status` is `PLACED` or `CANCELLED`. `total` is `NUMERIC(12,2)`. `UNIQUE (id, shop_id)`. `(created_by, shop_id)` → `app_user (id, shop_id)`. The table is not named `order` because that is a reserved word in SQL. |
| `order_item` | id, **shop_id**, order_id, product_id, quantity, unit_price | `(order_id, shop_id)` → `customer_order (id, shop_id)`. `(product_id, shop_id)` → `product (id, shop_id)`. `quantity` has `CHECK (quantity > 0)`. `unit_price` is the price at the time of sale. |
| `stock_movement` | id, shop_id, product_id, change, reason, order_id (nullable), note (nullable, up to 200 characters, set by restock), created_by, created_at | `(product_id, shop_id)` → `product (id, shop_id)`. `(order_id, shop_id)` → `customer_order (id, shop_id)` when `order_id` is not null. `(created_by, shop_id)` → `app_user (id, shop_id)`. `change` is a non-zero integer. `reason` is `OPENING_STOCK`, `RESTOCK`, `SALE` or `CANCEL`. (A manual-correction reason can be added with a future stock-correction feature; Phase 1 has nothing that would write it.) Rows are append-only and never updated or deleted. |

**Tenant-aware foreign keys.** Every row that points at another tenant-owned row carries `shop_id`. The foreign key covers **both** the ID and `shop_id`, so it points at the pair `(id, shop_id)` in the parent table. The database therefore **rejects** an order line or stock movement that points at another shop's product, order or user, even if a bug in the Java code tries to write one. Isolation of reads still depends on the application: every query filters by `shopId` (section 6). Row-level security (RLS) would enforce reads in the database too; it is deliberately left as later hardening.

All constraints have explicit names (for example `uq_product_shop_sku` and `uq_app_user_email`), because the error mapping in section 7 relies on them.

**Indexes:**

- `product (shop_id, active)`
- `customer_order (shop_id, created_at DESC)`
- `stock_movement (product_id, created_at DESC)`
- `order_item (order_id)`

**Ledger invariant:** for every product, the sum of its `stock_movement.change` values equals its `stock_qty`. **This is enforced by the application, not the database.** It holds because every stock change goes through the one `StockRepository` described in section 5, and each of its methods writes the matching movement in the same transaction. A test checks the invariant after a mixed sequence of operations.

**Java mapping:**

- JPA entities for tables. Enums for `role`, `status` and `reason`, stored as strings.
- `BigDecimal` for all money.
- Request and response DTOs are Java `record`s. Entities are never returned from controllers.

---

## 4. API

The base path is `/api`, and all requests and responses are JSON. Authentication is an `Authorization: Bearer {token}` header unless marked public.

**Health check:** `GET /actuator/health` is public and returns only `{"status":"UP"}` or `DOWN`, including a database check, with no details. Azure's health check and the deployment smoke test use it. No other Actuator endpoints are exposed.

| Method and path | Access | Behavior |
|---|---|---|
| `POST /auth/register` | public | Creates a shop and its OWNER in one transaction. Returns 201 with a token. |
| `POST /auth/login` | public | Returns `{ accessToken, expiresIn }` |
| `POST /users` | OWNER | Creates a STAFF user in the caller's shop |
| `GET /users` | OWNER | Lists the shop's users |
| `GET /products` | OWNER, STAFF | Paginated. Filters: `?lowStock=true` (stock ≤ threshold), `?q=` (name or SKU contains), `?includeArchived=true` (OWNER only). |
| `GET /products/{id}` | OWNER, STAFF | Returns the product. 404 if it's not in the caller's shop. |
| `POST /products` | OWNER | Creates a product. `stock_qty` starts at the given value, and if that is above 0 an `OPENING_STOCK` movement is recorded. |
| `PUT /products/{id}` | OWNER | Updates name, SKU, price and threshold. **Stock cannot be changed here**; that goes through restock or orders. |
| `DELETE /products/{id}` | OWNER | Archive: sets `active=false`. Returns 204. |
| `POST /products/{id}/restock` | OWNER | `{ quantity (1–100000), note? }` adds stock and records a `RESTOCK` movement |
| `GET /products/{id}/movements` | OWNER | The product's stock history, paginated, newest first |
| `POST /orders` | OWNER, STAFF | `{ items: [{ productId, quantity }] }` places an order atomically (section 5) |
| `GET /orders`, `GET /orders/{id}` | OWNER, STAFF | Paginated, newest first. The detail view includes the order lines. |
| `POST /orders/{id}/cancel` | OWNER | Cancels the order and returns its stock (section 5) |

**Pagination:** Spring `Pageable` with `?page=&size=`. The default size is 20 and the maximum is 100.

---

## 5. Core logic: orders and stock

### Rule: JPA never writes `stock_qty`

All stock changes go through **one class, `StockRepository`**. It has four methods: `deduct` (sale), `restock`, `returnStock` (cancellation) and `recordOpeningStock`. Each of the first three runs a single SQL `UPDATE` plus the matching `stock_movement` insert. `recordOpeningStock` only writes the `OPENING_STOCK` movement, because the opening value is set when the product is inserted. Nothing else in the codebase changes stock. Three safeguards make JPA's in-memory state safe around those direct updates:

1. **The `Product` entity maps `stock_qty` with `@Column(insertable = true, updatable = false)`.** Hibernate can set the opening value on insert, but never includes `stock_qty` in an `UPDATE`.
   - This closes a lost-update bug that also exists **across** transactions, not only inside one. `PUT /products/{id}` loads a product with stock 5. A concurrent order then commits stock 3. Without this mapping, Hibernate's default full-row `UPDATE` for the `PUT` would write the stale 5 back and silently erase the sale.
2. **The update methods are `@Modifying(flushAutomatically = true, clearAutomatically = true)`.** Pending entity changes are flushed before the SQL runs, and the persistence context is cleared afterwards, so no stale `Product` instance survives in the transaction.
3. **After deducting stock, the order service re-reads the products it needs** (price and name) by ID. The rows are already locked by the `UPDATE`, so these reads return the current committed values.

**Tests for this rule:**
- In one transaction: load a product, deduct stock through `StockRepository`, change the product's name through the entity, and commit. The final stock must be the deducted value.
- Concurrently: a `PUT` and an order on the same product. The stock change must survive.

### Placing an order (one `@Transactional` service method)

1. **Validate the request:**
   - there are 1–50 items
   - each quantity is 1–1000
   - no product ID appears twice (400, rather than silently merging the lines)
2. **Sort the items by `productId` ascending.** This avoids the main deadlock between two multi-product orders. It is not a guarantee against all deadlocks: that holds only if **every** writer that touches several products locks them in ascending ID order. Cancellation does (below), and any future multi-product writer must too. A deadlock that still happens becomes a 409 `CONCURRENT_UPDATE` that the client can retry. It never becomes a 500.
3. **For each item, run one conditional update:**
   ```sql
   UPDATE product
      SET stock_qty = stock_qty - :qty, updated_at = now()
    WHERE id = :id AND shop_id = :shopId AND active = true AND stock_qty >= :qty
   ```
   If 0 rows are updated, re-read the product within the shop and throw:
   - `NotFoundException` if it doesn't exist in this shop or is archived
   - `InsufficientStockException(productId, requested, available)` otherwise
4. **Read the current prices** of the (now locked) products and build the `order_item` rows with `unit_price`. Calculate `total` with `BigDecimal`, using scale 2 and `RoundingMode.HALF_UP`.
5. **Save the order and its items**, plus one `SALE` stock movement (negative change) per line.
6. **Any exception rolls back the whole transaction.** There are no partial orders.

**Why not "read, check in Java, then save"?** Two concurrent requests can both read `stock=5` and both pass the check, and the store sells 8 of a product it had 5 of. The conditional `UPDATE` makes the check and the deduction one atomic step. PostgreSQL's row lock makes the second transaction wait, then re-check the condition against the committed value. The `CHECK (stock_qty >= 0)` constraint is a second safety net.

Isolation level: the PostgreSQL default, `READ COMMITTED`. That is enough because the guard lives in the `UPDATE`'s `WHERE` clause.

**Archiving during an order.** Archiving (`active=false`) and the stock deduction both lock the same product row, so they happen one after the other:
- If the order's deduction goes first, the order completes. It started while the product was still for sale, so that's acceptable.
- If the archive goes first, the order's `WHERE active = true` fails, and the order returns 404 for that product.

Both outcomes are defined and tested.

### Cancelling an order (`@Transactional`)

1. Lock the order: `SELECT ... FOR UPDATE`, filtered by shop.
2. If its status is not `PLACED`, return 409 `ORDER_NOT_CANCELLABLE`.
3. For each line, add the stock back in **ascending product ID order** (the same rule as orders) and record a `CANCEL` movement (positive change). **Archived products still get their stock back.** Cancelling reverses a sale; archiving only stops *new* sales, and must not falsify stock history.
4. Set `status=CANCELLED` and `cancelled_at=now()`.

**Cancelling twice** returns 409 and never adds the stock back twice. The row lock guarantees this even with two concurrent cancel requests.

### Restock

A conditional update adds the quantity, and a `RESTOCK` movement is recorded in the same transaction. Restocking an archived product returns 404.

---

## 6. Security

### Registration and login

- **Password rules:** at least 8 characters, and **at most 72 bytes once encoded as UTF-8**. 72 bytes is BCrypt's input limit, and 72 characters can be more than 72 bytes (an emoji is 4 bytes). A custom validator checks the byte length and rejects longer passwords with 400 `VALIDATION_FAILED`. Longer passwords are never silently truncated. Tests cover 72 ASCII bytes (accepted), 73 bytes (rejected), and a multi-byte password under 72 characters but over 72 bytes (rejected).
- **Hashing:** BCrypt with strength 10.
- **Login response:** unknown emails and wrong passwords get the same 401 body. To keep response times similar, unknown emails are still checked against a dummy BCrypt hash.

### Tokens

- **JWT:** HS256, signed with `JWT_SECRET` (at least 32 bytes). The app fails at startup if the secret is missing or too short.
- **Claims:** `sub` (userId), `shopId`, `role`, `iat`, `exp`. Tokens last 1 hour.
- **Validation:** Spring Security's `oauth2-resource-server` with a `NimbusJwtDecoder` configured with the same secret. There is no hand-written token parsing.
- **Stateless:** no sessions. CSRF protection is disabled because there are no cookies.
- **Token claims can go stale. This is a documented Phase 1 limitation.** The `role` and `shopId` in a token are trusted until it expires (at most 1 hour). Phase 1 has **no endpoint that deletes, deactivates or demotes a user**, so a stale claim can't currently grant access someone should have lost. Phase 2, which adds "remove staff", must at the same time switch to resolving the user's current state from the database on each request (a user-status lookup, or a per-user token version). The README states this limitation.
- **Security errors use the same format as every other error.** Spring Security rejects requests in its filters, before any controller runs, so `@RestControllerAdvice` never sees those failures. A custom `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) write the same `ProblemDetail` shape as section 7. `@PreAuthorize` failures inside controllers also go to the 403 handler. Tests: a missing token, a malformed token, a token with a bad signature, an expired token, and a valid STAFF token on an owner endpoint. All five must return the documented status **and** the exact JSON shape.

### Authorization and isolation

- **Roles** map to authorities (`ROLE_OWNER`, `ROLE_STAFF`). Owner-only methods use `@PreAuthorize("hasRole('OWNER')")`.
- **Tenant isolation:**
  - `shopId` is taken **only** from the validated token, through a single factory method, `AuthUser.from(jwt)`. It is never taken from the request path, body or query.
  - Every repository method used by services takes `shopId` and filters by it.
  - A cross-shop access returns **404, not 403**, so the API doesn't reveal that the resource exists.

### Rate limiting and other protections

- **Login rate limit** (applied to `/auth/login` and `/auth/register`) has two counters, both in bounded in-memory Caffeine caches (at most 10,000 entries each; entries expire after 15 minutes):
  - **per email:** 5 failed logins in 15 minutes → 429. This slows guessing one account's password.
  - **per client IP:** 30 attempts in 15 minutes, successful or not → 429. This stops one client from spraying many emails to get around the per-email limit.
  - **Client IP:** the app runs behind Azure's front end, which **appends** the real client address to `X-Forwarded-For`. Clients can put any fake values at the start of that header, so the limiter uses the **last** entry (the one Azure added), never the first. When there is no header (local development), it uses the socket address. A test sends a forged header and checks that the fake value is ignored.
  - **Known limits:** the counters are in memory, so they reset on restart and aren't shared across instances. Multiple instances would need Redis. A distributed attack from many IPs isn't stopped. The README states all three.
- **CORS** is closed in Phase 1.
- **Secrets:** `.env` is gitignored, and on Azure the values live in App Service settings.
- **Swagger UI is an interactive API demo**, not a read-only page. Anyone can open it, and after logging in with a demo account through its "Authorize" button, they can make real changes to the demo shop. That's why the demo shop's data is expendable (section 8).

---

## 7. Errors

Every error is an RFC 9457 `ProblemDetail` with an extra `code` property. Errors from controllers and services come from one `@RestControllerAdvice`. The 401 and 403 errors from the security filters come from the entry point and access-denied handler in section 6, which use the same shape:

| HTTP | `code` | Trigger |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation or a malformed body. Includes an `errors: [{field, message}]` list. |
| 401 | `UNAUTHORIZED` | Token is missing, invalid or expired, or login failed. Produced by the security entry point in section 6, not the controller advice. |
| 403 | `FORBIDDEN` | The role isn't allowed. Produced by the access-denied handler in section 6. |
| 404 | `NOT_FOUND` | Missing, archived where relevant, or in another shop |
| 409 | `INSUFFICIENT_STOCK` | Includes `productId`, `requested` and `available` |
| 409 | `ORDER_NOT_CANCELLABLE`, `DUPLICATE_SKU`, `EMAIL_TAKEN` | Business-rule conflicts. The two duplicate cases are mapped **by the name of the violated constraint** (`uq_product_shop_sku` → `DUPLICATE_SKU`, `uq_app_user_email` → `EMAIL_TAKEN`), taken from the underlying `ConstraintViolationException`. Any other integrity violation is a bug and becomes a logged 500, never a misleading 409. |
| 409 | `CONCURRENT_UPDATE` | A PostgreSQL deadlock or serialization failure. The client may retry. |
| 429 | `RATE_LIMITED` | Login throttle |
| 500 | `INTERNAL_ERROR` | A generic message. The stack trace is logged, never returned. |

**Logging:** one line per request (method, path, status, duration and user ID) plus a request ID. Passwords, tokens and request bodies are never logged.

---

## 8. Testing, CI and deployment

### Tests

- **Unit tests** (no Spring context): total calculation and rounding, order validation rules, and cancel state rules.
- **Integration tests** (`@SpringBootTest` + MockMvc against a real PostgreSQL test database, cleaned between tests):
  - every endpoint's success path and its documented error codes
  - security: the five token cases from section 6 (each checking status **and** JSON shape), and identical login failure responses
  - **cross-shop isolation for reads and writes.** Shop B's owner and staff try, against shop A's resources: to read, update, archive and restock a product; to place an order containing shop A's product ID; and to read and cancel shop A's order. Every attempt by shop B's **owner** must return 404. Shop B's **staff** get 404 on the endpoints staff may use, and 403 on owner-only endpoints, because the role check runs before any lookup, so the 403 reveals nothing about shop A's data. Afterwards, shop A's data (stock, movements, orders) must be unchanged.
  - **the database backstop:** a repository-level test tries to insert an `order_item` and a `stock_movement` that point at another shop's product, bypassing the service. The tenant-aware foreign key must reject both.
- **Concurrency test:** a product has stock 5. 20 threads each place an order for 1 unit through the service at the same time. Exactly 5 succeed, 15 get `InsufficientStockException`, the final stock is 0, and there are exactly 5 `SALE` movements.
- **Lock-ordering test:** two threads repeatedly place orders for products {A, B} and {B, A}, while a third thread cancels earlier orders. There must be no deadlock errors across 100 iterations. This proves the ordering rule for today's writers; it is not a proof that deadlocks are impossible.
- **Archive race test:** an order and an archive of the same product run at the same moment. Both outcomes described in section 5 are acceptable, and nothing else is.
- **Stale entity tests:** as described in section 5.
- **Ledger invariant test:** after restock, orders and cancellations, `SUM(change) == stock_qty` for every product.
- **Coverage:** a JaCoCo report is published in CI. There is no fixed percentage target; the rule is that every behavior in sections 5–7 has a test.

### Local development (Windows, no Docker)

- Temurin JDK 21 and PostgreSQL 16 installed natively.
- Tests read the database URL from environment variables (`TEST_DB_URL` and so on).

### CI (GitHub Actions)

- On every push and pull request: a `postgres:16` service container, then `./mvnw verify`.
- Test and coverage reports are uploaded as build artifacts.
- The README shows the status badge.
- **CD:** on push to `main`, after `verify` passes, the jar is deployed to Azure App Service using `azure/webapps-deploy`. Authentication is GitHub OIDC federated credentials if practical, otherwise a publish profile stored as a secret.

### Azure

- **App Service plan: measure before committing.** Azure's Free (F1) tier is shared compute with daily CPU quotas and no always-on, so it can't be counted on for an interview demo. Plan: deploy to F1 first and record the app's startup time and memory use. Then show the author the results next to the current monthly price of B1, and the author chooses. The README states which tier is in use and what that means for response time.
- **Database:** Azure Database for PostgreSQL Flexible Server, B1ms. It is free for 12 months on an Azure free account.
  - **Firewall:** do **not** use "Allow public access from any Azure service": Microsoft notes that this includes other customers' subscriptions. Allow only the App Service app's listed outbound IP addresses. On the shared Free and Basic tiers those addresses are shared with other apps on the same Azure infrastructure, so the firewall reduces exposure but isn't the main defense.
  - **Always, whatever the firewall:** TLS required (`sslmode=require`), a dedicated database user with a long generated password (not the server admin), and least privilege (owns only the app's schema).
- **Settings:** `JWT_SECRET`, `DB_URL`, `DB_USER`, `DB_PASSWORD` and `SPRING_PROFILES_ACTIVE=prod,demo`.
- **Migrations:** Flyway runs automatically on startup.
- **Demo data: seeded once, never reset automatically.**
  - The `demo` profile runs an idempotent seeder on startup. The demo shop is identified by its owner's email, `owner@demo.stockroom.dev`. Emails are globally unique, so nobody else can register it once the demo exists. If that user doesn't exist, the seeder creates the shop "Demo Sari-Sari Store", one OWNER, one STAFF and 15 products. If it already exists, **it does nothing**: it never resets data or changes passwords, so a deploy can't disrupt someone who is using the demo.
  - The demo profile is enabled only on the single public demo deployment, which **is** the demo environment and holds no real shop data. Any real deployment for a customer runs without `demo`.
  - **Reset is a manual, guarded operation:** a SQL script in `ops/reset-demo.sql` that deletes only rows whose `shop_id` is the demo shop's. It finds that shop through the owner email **and** requires the shop name to match exactly; otherwise it raises an error and deletes nothing. It is run by hand when needed, never by CI.
  - The demo logins are in the README.
- **Cost gate:** no Azure resource is created until the author has seen the expected monthly cost and approved it.

### Repository

- Public: `github.com/WrenzLaylo/stockroom-api`
- **README:** the pitch, live link and demo logins, an architecture diagram, a section on "how concurrent orders can't oversell", how to run it locally, tests and CI, and what I'd do next.
- **`STUDY_GUIDE.md`:** about 30 interview questions with model answers, and a practice change for the author to do alone: add a "maximum 10 units per order line per product" rule, with a test.

---

## 9. Decisions and alternatives considered

| Decision | Alternatives | Why this choice |
|---|---|---|
| Single service (monolith) | Products and orders as separate microservices | Entry-level scope; one deployment. The README explains where it would split (orders and inventory) and what that would need: a saga or outbox, and idempotency. |
| Conditional `UPDATE` for stock | Optimistic locking with `@Version`; `SELECT ... FOR UPDATE` then update in Java; `SERIALIZABLE` isolation | Atomic, simple, no retry loop, easy to explain. Optimistic locking would need retries under contention. |
| Shared database, `shop_id` column, tenant-aware composite foreign keys | A schema per tenant; a database per tenant; row-level security now | The simplest multi-tenancy that works for a small SaaS. Composite foreign keys cost little and let the database reject cross-shop *writes*. Row-level security would also protect *reads* in the database; it's the next hardening step, left out to keep Phase 1 learnable. |
| HS256 with a shared secret | RS256 with a key pair; an external identity provider (Entra ID, Auth0) | One service both issues and validates tokens, so a shared secret is enough. RS256 or an identity provider fits once other services need to verify tokens. |
| Real PostgreSQL in tests | H2; Testcontainers | H2 doesn't behave like Postgres for locking and constraints. Testcontainers needs Docker, which isn't available locally. CI uses a Postgres service container, which is equivalent. |
| No refresh tokens | Refresh token rotation | Phase 1 has no UI, so it isn't needed. It will be added with Angular in Phase 2. |

---

## 10. Review log

**Review 1: GPT 6 Astra, 2026-09-28 (design only; no code or deployment tested).** Changes made:

| # | Finding | Resolution |
|---|---|---|
| B1 | Tenant isolation not enforced on write paths | Composite tenant-aware foreign keys (section 3), and cross-shop tests for reads **and** writes plus a database backstop test (section 8). The limits of isolation are now stated: reads are enforced by the application only, with RLS left for later. |
| B2 | A native stock `UPDATE` vs stale JPA entities | `StockRepository` is the only writer of stock, `stock_qty` has `updatable=false`, and the update methods flush and clear the persistence context (section 5). Also fixed a related **cross-transaction** lost update on `PUT /products` that the review didn't list. |
| B3 | The demo reset could disrupt the live deployment | The seeder is idempotent and never resets. Reset is a manual, guarded script scoped to the demo shop (section 8). |
| B4 | Security 401/403 errors bypass the controller advice | A custom entry point and access-denied handler, plus tests of both status and shape for all five token cases (sections 6 and 7). |
| S1 | Generic integrity-violation mapping | Mapped by constraint name, with anything else as a 500. The ledger is stated to be an application invariant (sections 3 and 7). |
| S2 | Password limit in characters vs bytes | A UTF-8 byte-length validator, with boundary tests (section 6). |
| S3 | Stale role and shop claims | **Documented, not changed.** Phase 1 has no endpoint to remove or demote a user, so a stale claim can't grant revoked access yet. Phase 2's "remove staff" feature must add a per-request user-state check at the same time (section 6). |
| S4 | The login limiter is easy to bypass and has no size bound | Per-email plus per-IP counters, both bounded caches, with the last-hop client IP and a forged-header test (section 6). |
| S5 | "Read-only" Swagger | Reworded to an interactive API demo (section 6). |
| S6 | Database firewall too broad | Explicit outbound IPs instead of "allow any Azure service", with the shared-IP caveat, plus TLS and a dedicated least-privilege user (section 8). |
| S7 | Broken Bearer-token code span | Reworded (section 4). |
| Scope | Health endpoint; pinned versions | Added a minimal Actuator health endpoint (section 4); versions are pinned at scaffold time (section 1). RLS, Redis, refresh tokens, Angular and a Dockerfile stay out. |
| Q1 | "Deadlock-free" too absolute | Reworded to a lock-ordering rule that all multi-product writers must follow; a residual deadlock becomes 409 `CONCURRENT_UPDATE`; the archive race is defined (sections 5 and 7). |
| Q2 | Stock returned to archived products on cancel | Kept, with the reason stated (section 5). |
| Q4 | Global unique email | Stated as a deliberate product constraint (section 3). |
| Q5 | `ADJUST` for initial stock | Renamed to `OPENING_STOCK`, and `ADJUST` dropped because nothing in Phase 1 writes it (sections 3 and 4). |
| Q6 | Azure F1 reliability | Measure on F1 first; the author picks F1 or B1 with real numbers and the current price (section 8). |
