# Stockroom API: Design Spec (Phase 1)

- **Date:** 2026-09-28
- **Status:** Draft, awaiting external review
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
- **Answers to the open questions in section 10.**

Please don't suggest adding features "because they're nice". Every addition is code the author must learn.

---

## 1. Summary

**Stockroom API** is a multi-tenant REST API for small shops to manage products, stock and orders.

The core promise:

> Stock can never go negative, even when two orders arrive at the same moment, and each shop's data is isolated from every other shop.

**Stack:**

- Java 21
- Spring Boot (latest stable at scaffold time), Spring Web, Spring Data JPA, Spring Security (OAuth2 resource server for JWT), Bean Validation
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
| `app_user` | id, shop_id → shop, email, password_hash, role, created_at | `email` is unique globally (case-insensitive, stored lowercase). `role` is `OWNER` or `STAFF`. Passwords are stored as BCrypt hashes only. |
| `product` | id, shop_id → shop, sku, name, price, stock_qty, low_stock_threshold, active, created_at, updated_at | `UNIQUE (shop_id, sku)`. `price` is `NUMERIC(12,2)` with `CHECK (price >= 0)`. `stock_qty` has `CHECK (stock_qty >= 0)`. `low_stock_threshold` has `CHECK (>= 0)` and defaults to 5. `active` defaults to true. |
| `customer_order` | id, shop_id → shop, status, total, created_by → app_user, created_at, cancelled_at | `status` is `PLACED` or `CANCELLED`. `total` is `NUMERIC(12,2)`. The table is not named `order` because that is a reserved word in SQL. |
| `order_item` | id, order_id → customer_order, product_id → product, quantity, unit_price | `quantity` has `CHECK (quantity > 0)`. `unit_price` is the price at the time of sale. |
| `stock_movement` | id, shop_id → shop, product_id → product, change, reason, order_id → customer_order (nullable), created_by → app_user, created_at | `change` is a non-zero integer. `reason` is `RESTOCK`, `SALE`, `CANCEL` or `ADJUST`. Rows are append-only and never updated or deleted. |

**Indexes:**

- `product (shop_id, active)`
- `customer_order (shop_id, created_at DESC)`
- `stock_movement (product_id, created_at DESC)`
- `order_item (order_id)`

**Invariant:** for every product, the sum of its `stock_movement.change` values equals its `stock_qty`. That holds as long as every stock change goes through the service layer, with no direct updates. A test checks the invariant after a mixed sequence of operations.

**Java mapping:**

- JPA entities for tables. Enums for `role`, `status` and `reason`, stored as strings.
- `BigDecimal` for all money.
- Request and response DTOs are Java `record`s. Entities are never returned from controllers.

---

## 4. API

The base path is `/api`, and all requests and responses are JSON. Authentication is `Authorization: Bearer <jwt>` unless marked public.

| Method and path | Access | Behavior |
|---|---|---|
| `POST /auth/register` | public | Creates a shop and its OWNER in one transaction. Returns 201 with a token. |
| `POST /auth/login` | public | Returns `{ accessToken, expiresIn }` |
| `POST /users` | OWNER | Creates a STAFF user in the caller's shop |
| `GET /users` | OWNER | Lists the shop's users |
| `GET /products` | OWNER, STAFF | Paginated. Filters: `?lowStock=true` (stock ≤ threshold), `?q=` (name or SKU contains), `?includeArchived=true` (OWNER only). |
| `GET /products/{id}` | OWNER, STAFF | Returns the product. 404 if it's not in the caller's shop. |
| `POST /products` | OWNER | Creates a product. `stock_qty` starts at the given value, and if that is above 0 an `ADJUST` movement is recorded. |
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

### Placing an order (one `@Transactional` service method)

1. **Validate the request:**
   - there are 1–50 items
   - each quantity is 1–1000
   - no product ID appears twice (400, rather than silently merging the lines)
2. **Sort the items by `productId` ascending.** Concurrent orders then lock rows in the same order, which prevents deadlocks.
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

### Cancelling an order (`@Transactional`)

1. Lock the order: `SELECT ... FOR UPDATE`, filtered by shop.
2. If its status is not `PLACED`, return 409 `ORDER_NOT_CANCELLABLE`.
3. For each line, add the stock back (in product ID order) and record a `CANCEL` movement (positive change). Archived products still get their stock back.
4. Set `status=CANCELLED` and `cancelled_at=now()`.

**Cancelling twice** returns 409 and never adds the stock back twice. The row lock guarantees this even with two concurrent cancel requests.

### Restock

A conditional update adds the quantity, and a `RESTOCK` movement is recorded in the same transaction. Restocking an archived product returns 404.

---

## 6. Security

### Registration and login

- **Password rules:** 8–72 characters. 72 bytes is BCrypt's input limit, and anything longer is silently ignored.
- **Hashing:** BCrypt with strength 10.
- **Login response:** unknown emails and wrong passwords get the same 401 body. To keep response times similar, unknown emails are still checked against a dummy BCrypt hash.

### Tokens

- **JWT:** HS256, signed with `JWT_SECRET` (at least 32 bytes). The app fails at startup if the secret is missing or too short.
- **Claims:** `sub` (userId), `shopId`, `role`, `iat`, `exp`. Tokens last 1 hour.
- **Validation:** Spring Security's `oauth2-resource-server` with a `NimbusJwtDecoder` configured with the same secret. There is no hand-written token parsing.
- **Stateless:** no sessions. CSRF protection is disabled because there are no cookies.

### Authorization and isolation

- **Roles** map to authorities (`ROLE_OWNER`, `ROLE_STAFF`). Owner-only methods use `@PreAuthorize("hasRole('OWNER')")`.
- **Tenant isolation:**
  - `shopId` is taken **only** from the validated token, through a single `CurrentUser` component. It is never taken from the request path, body or query.
  - Every repository method used by services takes `shopId` and filters by it.
  - A cross-shop access returns **404, not 403**, so the API doesn't reveal that the resource exists.

### Rate limiting and other protections

- **Login rate limit:** 5 failures per email per 15 minutes returns 429. It uses an in-memory map (Caffeine) because Phase 1 runs a single instance. Multiple instances would need Redis, and the README says so.
- **CORS** is closed in Phase 1.
- **Secrets:** `.env` is gitignored, and on Azure the values live in App Service settings.
- **Swagger UI** is public but read-only. It shows an "Authorize" button for trying the API with the demo logins.

---

## 7. Errors

Every error is an RFC 9457 `ProblemDetail`, produced by one `@RestControllerAdvice`, with an extra `code` property:

| HTTP | `code` | Trigger |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation or a malformed body. Includes an `errors: [{field, message}]` list. |
| 401 | `UNAUTHORIZED` | Token is missing, invalid or expired, or login failed |
| 403 | `FORBIDDEN` | The role isn't allowed |
| 404 | `NOT_FOUND` | Missing, archived where relevant, or in another shop |
| 409 | `INSUFFICIENT_STOCK` | Includes `productId`, `requested` and `available` |
| 409 | `ORDER_NOT_CANCELLABLE`, `DUPLICATE_SKU`, `EMAIL_TAKEN` | Business-rule conflicts. The two unique-constraint conflicts are caught as `DataIntegrityViolationException`, then mapped. |
| 429 | `RATE_LIMITED` | Login throttle |
| 500 | `INTERNAL_ERROR` | A generic message. The stack trace is logged, never returned. |

**Logging:** one line per request (method, path, status, duration and user ID) plus a request ID. Passwords, tokens and request bodies are never logged.

---

## 8. Testing, CI and deployment

### Tests

- **Unit tests** (no Spring context): total calculation and rounding, order validation rules, and cancel state rules.
- **Integration tests** (`@SpringBootTest` + MockMvc against a real PostgreSQL test database, cleaned between tests):
  - every endpoint's success path and its documented error codes
  - security: no token → 401, tampered or expired token → 401, STAFF on an owner endpoint → 403, cross-shop access → 404, and identical login failure responses
- **Concurrency test:** a product has stock 5. 20 threads each place an order for 1 unit through the service at the same time. Exactly 5 succeed, 15 get `InsufficientStockException`, the final stock is 0, and there are exactly 5 `SALE` movements.
- **Deadlock test:** two threads repeatedly place orders for products {A, B} and {B, A}. There must be no deadlock errors across 100 iterations.
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

- **App Service:** Linux, Java 21 SE, starting on the F1 (free) tier. On F1 the app sleeps when idle, so cold starts are slow, and the README says so. Upgrading to B1 is optional later.
- **Database:** Azure Database for PostgreSQL Flexible Server, B1ms. It is free for 12 months on an Azure free account. The server is reachable only by Azure services, and connections require SSL.
- **Settings:** `JWT_SECRET`, `DB_URL`, `DB_USER`, `DB_PASSWORD` and `SPRING_PROFILES_ACTIVE=prod,demo`.
- **Migrations:** Flyway runs automatically on startup.
- **Demo data:** the `demo` profile seeds "Demo Sari-Sari Store" with one OWNER, one STAFF and about 15 products. It resets on each deployment, and the demo logins are in the README.
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
| Shared database, `shop_id` column | A schema per tenant; a database per tenant | The simplest multi-tenancy that works for a small SaaS. PostgreSQL row-level security is a possible later hardening step. |
| HS256 with a shared secret | RS256 with a key pair; an external identity provider (Entra ID, Auth0) | One service both issues and validates tokens, so a shared secret is enough. RS256 or an identity provider fits once other services need to verify tokens. |
| Real PostgreSQL in tests | H2; Testcontainers | H2 doesn't behave like Postgres for locking and constraints. Testcontainers needs Docker, which isn't available locally. CI uses a Postgres service container, which is equivalent. |
| No refresh tokens | Refresh token rotation | Phase 1 has no UI, so it isn't needed. It will be added with Angular in Phase 2. |

---

## 10. Open questions for the reviewer

1. **Stock guard:** is the conditional `UPDATE` under `READ COMMITTED`, with items sorted by ID, correct and deadlock-free for multi-item orders? Are there edge cases (for example archiving a product while an order runs) that it gets wrong?
2. **Cancelling orders for archived products:** stock is returned to archived products. Is that the right behavior, or should archived products refuse the returned stock?
3. **Tenant isolation:** is "`shopId` only from the token, plus every repository query filtered by it" robust enough for Phase 1? Or should Hibernate filters or Postgres row-level security be used from day one?
4. **Unique emails:** `app_user.email` is unique across all shops, so one person can't own two shops with the same email. Acceptable for Phase 1?
5. **The `ADJUST` movement on product creation:** is recording initial stock as a movement the right way to keep the ledger invariant, or is it confusing?
6. **Azure F1:** does the free tier reliably run a Spring Boot 3/4 app on Java 21 (memory and CPU-minute limits)? Should it start on B1 instead?
7. **Scope:** is anything here more than an entry-level candidate can credibly explain? Is anything missing that an interviewer for a Java/Spring cloud role would expect to see (for example Actuator health checks, a Dockerfile, or OpenAPI annotations)?
8. **Naming:** any concerns with `stockroom-api`?
