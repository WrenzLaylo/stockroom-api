# Stockroom API

[![CI](https://github.com/WrenzLaylo/stockroom-api/actions/workflows/ci.yml/badge.svg)](https://github.com/WrenzLaylo/stockroom-api/actions/workflows/ci.yml)

A multi-tenant inventory and orders REST API for small shops, built with **Java 21, Spring Boot 4.1.1, Spring Security, JPA, Flyway and PostgreSQL**, and designed for **Azure** (App Service + Azure Database for PostgreSQL).

> **The promise:** stock can never go negative, even when two orders arrive at the same moment, and each shop's data is isolated from every other shop.

## Try it

- **Live Swagger UI:** see "Live demo" below (added once deployed)
- **Demo logins** (a shared demo shop; anyone can change it):
  - Owner: `owner@demo.stockroom.dev` / `demo-owner-2026`
  - Staff: `staff@demo.stockroom.dev` / `demo-staff-2026`
- Call `POST /api/auth/login`, copy `accessToken`, click **Authorize**, and paste it.

## How it works

```
Client ──HTTP/JSON──▶ Controller ──▶ Service ──▶ Repository ──▶ PostgreSQL
                         │              │            │
            JWT checked by Spring   business rules   StockRepository: the ONLY
            Security; shopId comes  + transactions   code that changes stock
            only from the token
```

| Area | Choice |
|---|---|
| Tenancy | One database, `shop_id` on every row, **composite foreign keys** `(id, shop_id)`, so the database rejects rows that point across shops |
| Auth | Stateless JWT (HS256, 1 hour), roles `OWNER` and `STAFF`, `@PreAuthorize` on owner-only endpoints |
| Errors | RFC 9457 `ProblemDetail` with a stable `code` (`INSUFFICIENT_STOCK`, `DUPLICATE_SKU`, …) |
| Schema | Flyway migrations; Hibernate only validates |
| History | Append-only `stock_movement` ledger: the sum of changes always equals current stock |

## How two orders can't oversell

The naive way (read stock, check it in Java, save the new number) lets two orders both read `5`, both pass, and sell 8 of 5. Instead, each order line is **one** SQL statement:

```sql
UPDATE product SET stock_qty = stock_qty - :qty
WHERE id = :id AND shop_id = :shop AND active = true AND stock_qty >= :qty
```

The check and the deduction are atomic, PostgreSQL's row lock makes the second order wait and then re-check, and `CHECK (stock_qty >= 0)` is a second safety net. Order lines (and cancellations) lock products in ascending product ID order, so two orders touching the same products don't deadlock on each other; a deadlock that still happens becomes a retryable `409 CONCURRENT_UPDATE`, never a 500. `ConcurrencyTest` proves it: **20 threads buy 1 unit of a product with 5 in stock, and exactly 5 succeed.**

A related trap: JPA saving a whole `Product` row can write back stale values. `stock_qty` is mapped `updatable = false`, so only the guarded UPDATE ever changes it, and `@DynamicUpdate` makes an edit write only the columns it changed, so it can't un-archive a product (race tests cover both).

**How I know the tests aren't passing by luck:** each protection was removed on purpose and its test watched fail: no stock guard → the database's `CHECK` rejects the oversell; no `@DynamicUpdate` → an edit un-archives a product; no row lock on cancel → stock is returned twice; no shop filter → another shop's data is readable (and cross-shop writes hit the composite foreign key).

## Versions

Pinned deliberately (upgrades are separate, reviewed changes): Spring Boot 4.1.1, springdoc-openapi 3.1.1, Java 21 (Temurin), PostgreSQL 16, JaCoCo 0.8.15.

## Run it locally

Requirements: JDK 21 and PostgreSQL 16.

```bash
# as the postgres user: create role stockroom (dev) and stockroom_test (tests only), and one database each
export JWT_SECRET="any-local-secret-of-at-least-32-bytes"
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run     # http://localhost:8080/swagger-ui/index.html
./mvnw verify                                          # 113 tests against real Postgres, plus a JaCoCo report
```

Tests refuse to run against any database other than `stockroom_test`, because they wipe it between tests.

## API

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | anyone |
| GET, POST | `/api/users` | owner |
| GET | `/api/products`, `/api/products/{id}` | owner, staff |
| POST, PUT, DELETE | `/api/products`, `/api/products/{id}` | owner |
| POST, GET | `/api/products/{id}/restock`, `/api/products/{id}/movements` | owner |
| POST, GET | `/api/orders`, `/api/orders/{id}` | owner, staff |
| POST | `/api/orders/{id}/cancel` | owner |
| GET | `/actuator/health` | anyone |

## Known limits (deliberate for Phase 1)

- **Token claims are trusted for up to 1 hour.** There's no endpoint to remove or demote staff yet; when that's added, each request will re-check the user's status.
- **Login rate limits live in memory:** fine for one instance, reset on restart. Multiple instances would need Redis, and a distributed attack from many IPs isn't stopped.
- **Read isolation is enforced by the application** (every query filters by `shop_id`); PostgreSQL row-level security would be the next hardening step.
- **Limits:** prices up to 999,999.99; stock up to 1,000,000,000 units per product; decimals in whole-number fields are rejected, never rounded.
- **Azure tier:** see "Live demo" for the tier in use and what it means for response time.

## What I'd do next

An Angular front end, a "remove staff" flow with per-request user checks, row-level security, and splitting orders from inventory only if they ever need to scale separately (which would need an outbox and idempotent handlers).

---

Built by [Wrenz Laylo](https://github.com/WrenzLaylo) with Claude as a coding assistant. Every file was reviewed, is covered by tests, and I can explain and change any part of it.
