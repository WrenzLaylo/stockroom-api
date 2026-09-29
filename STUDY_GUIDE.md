# Stockroom API: Study Guide

How to use this: cover the answer, say yours out loud, then compare. You're ready when you can do the **practice change** at the end without help.

## Walkthrough order

Follow one request, `POST /api/orders`, through the code:

1. `OrderController.place`: how does it get the user? (`@AuthenticationPrincipal Jwt` → `AuthUser.from`)
2. `OrderService.place`: the transaction and its 4 steps
3. `OrderRules.sortedLines`: why sort?
4. `StockRepository.deduct` and `apply`: the guarded UPDATE, flush and clear
5. `V1__init.sql`: the constraints behind it all
6. `GlobalExceptionHandler`: what the client sees when it fails
7. `ConcurrencyTest.twentyBuyersForFiveUnitsSellExactlyFive`: the proof

## Questions

**Architecture**

1. *Why one service instead of microservices?* Small scope, one deployment, one transaction for an order. I'd split orders from inventory only if they had to scale separately, and then I'd need an outbox or saga, because one database transaction would no longer cover both.
2. *What do the layers do?* Controller: HTTP and validation. Service: business rules and transactions. Repository: data access. The database: the final safety net (constraints).
3. *Why records for requests and responses but classes for entities?* Records are immutable data carriers, ideal for JSON. JPA entities need a no-argument constructor and mutable state for Hibernate.
4. *Why aren't entities returned from controllers?* It would leak internal fields (e.g. `passwordHash`), tie the API to the table shape, and risk lazy-loading surprises.

**Stock and transactions**

5. *Why a conditional UPDATE instead of read-check-save?* Read-check-save has a race: two requests both read 5. The UPDATE checks and deducts in one atomic statement, and the row lock serializes them.
6. *What isolation level, and why is it enough?* PostgreSQL's default, READ COMMITTED. After waiting on the row lock, PostgreSQL re-evaluates the WHERE clause against the committed row, so the guard sees the new stock.
7. *Why sort the lines by product ID?* So every transaction locks rows in the same order. Opposite orders ({A,B} vs {B,A}) could otherwise each hold one lock and wait forever for the other: a deadlock.
8. *Is it deadlock-free?* Only as long as every multi-product writer follows the ordering rule. Cancel does. If one ever happens anyway, it becomes a retryable 409 `CONCURRENT_UPDATE`.
9. *Why `updatable = false` on `stockQty`, and why `@DynamicUpdate`?* By default Hibernate updates all columns of a changed entity. Editing a product's name with an old copy would write the old stock back and erase a sale, or write `active = true` back over an archive. `updatable = false` means JPA never writes stock; `@DynamicUpdate` means an edit only writes the columns it changed. Either one alone stops the lost sale; the test only fails when both are removed.
10. *Why flush before and clear after the UPDATE?* Flush sends pending entity changes first; clear drops cached entities so nothing stale is used or re-saved afterwards.
11. *What happens if line 2 of an order fails?* An exception leaves the `@Transactional` method, so everything rolls back, including line 1's deduction and the order row.
12. *Why is the stock movement table append-only?* It's an audit trail: you can always explain the current stock. Test invariant: the sum of changes equals `stock_qty`.
13. *Why does cancelling return stock to archived products?* Cancelling reverses a sale; archiving only stops new sales. Refusing would falsify the history.
14. *How is double-cancel prevented?* `SELECT … FOR UPDATE` on the order row plus the status check: the second cancel waits, then sees CANCELLED and gets 409. Remove the lock and the simultaneous-cancel test returns stock twice.

**Money and input**

15. *Why `BigDecimal`, not `double`?* Binary floating point can't represent 0.10 exactly; money needs exact decimal arithmetic. Rounding is HALF_UP at 2 decimals.
16. *Why store `unit_price` on each order line?* So a later price change doesn't rewrite past orders.
17. *What happens if someone sends `"quantity": 1.9`?* 400. Jackson 3 would silently turn it into 1 by default; `JacksonConfig` makes decimals in whole-number fields an error.

**Security**

18. *How does login work?* The email and password are checked against a BCrypt hash, and a JWT is signed with HS256 containing userId, shopId and role, valid for 1 hour.
19. *Why BCrypt, and why the 72-byte limit?* BCrypt is slow on purpose and salted. It only reads the first 72 bytes, so longer passwords are rejected rather than silently truncated; it counts bytes, not characters.
20. *Why the same error for a wrong password and an unknown email?* So login can't be used to discover which emails have accounts. Even the timing is similar, using a dummy hash.
21. *Where does shopId come from, and why?* Only from the validated token. If it came from the request, a user could just ask for another shop's data.
22. *Why 404 instead of 403 for another shop's product?* 403 would confirm the product exists.
23. *Why is CSRF disabled?* CSRF abuses cookies the browser sends automatically. This API uses bearer tokens in a header and has no cookies.
24. *How do security errors get the same JSON as other errors?* Security rejects requests in filters, before controllers, so `ProblemSecurityHandlers` forwards them to the MVC exception resolver, and `GlobalExceptionHandler` builds every body.
25. *What's the weakness of the JWT approach here?* Claims are trusted until expiry, so a removed user keeps access for up to an hour. That's documented; a "remove staff" feature must add a per-request check.
26. *How does the rate limiter handle faked IPs?* It only reads `X-Forwarded-For` when `trust-forwarded-for` is on (production, behind Azure), and then only the last entry, which Azure appends.

**Multi-tenancy and the database**

27. *What do the composite foreign keys add?* The database itself rejects an order line or movement that points at another shop's product, even if the Java code has a bug. I saw it happen: removing the shop filter from restock made the database reject the write.
28. *What would row-level security add?* Database-enforced reads too. It's the next hardening step.
29. *Why Flyway with `ddl-auto: validate`?* The schema is versioned SQL you can review; Hibernate only checks that the entities match it and never changes the database.

**Testing and operations**

30. *Why test against real PostgreSQL instead of H2?* Locking, constraint names and SQL behavior differ, and the concurrency tests only mean something on the real database.
31. *How do you know the concurrency test isn't passing by luck?* Remove the `stock_qty >= ?` guard and it fails. I tried it. That kind of check also found a real bug: an error handler that crashed on database errors with no constraint name.
32. *What does CI do?* Every push starts Postgres in a container and runs `./mvnw verify` (all tests plus coverage). Pushes to main deploy exactly the tested jar to Azure, after CI passes.
33. *How did you use AI on this project?* Claude wrote the first version from a design I agreed and two external reviews. I reviewed every file, the tests prove the behavior, and I can change any part myself (see the practice change).

## Practice change (do this alone)

**Add a rule: a single order line may not exceed 10 units for products whose SKU starts with `FEEDS-`** (limited stock per customer).

Hints, only if stuck:
- Where do per-line rules live? (`OrderRules`, or the service once the product is known)
- The error should be `400 VALIDATION_FAILED` with field `items`.
- Write the test first in `OrderIntegrationTest`: 11 units of a `FEEDS-` product → 400 and stock unchanged; 11 units of another product → 201.

Done when `./mvnw verify` passes and you can explain why your rule sits where it does.
