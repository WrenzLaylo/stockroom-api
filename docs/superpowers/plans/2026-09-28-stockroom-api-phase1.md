# Stockroom API Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Stockroom API: a multi-tenant inventory and orders REST API in Java 21 / Spring Boot 4 that can't oversell stock, deployed to Azure with CI/CD, plus a README and a study guide the author can learn it from.

**Architecture:** One Spring Boot service with the classic layers: controller → service → repository → PostgreSQL. Every tenant-owned row carries `shop_id`, protected by composite foreign keys. All stock changes go through one `StockRepository` that uses a guarded SQL `UPDATE`. Security is stateless JWT (HS256) through Spring Security's resource server. Every error is an RFC 9457 `ProblemDetail` with a `code`.

**Tech Stack:**
- Java 21 (Temurin 21.0.12)
- Spring Boot **4.1.1**: Spring Framework 7.0.9, Spring Security 7.1.1, Hibernate 7.4.5, Jackson **3** (`tools.jackson`)
- Flyway 12.4, PostgreSQL 16 (driver 42.7.13)
- springdoc-openapi **3.1.1**, Caffeine 3.2.4
- JUnit **6.0.3**, AssertJ 3.27.7, MockMvc, JaCoCo **0.8.15**
- Maven wrapper (Maven 3.9.16)
- GitHub Actions: checkout@v7, setup-java@v6, upload-artifact@v7, azure/webapps-deploy@v3
- Azure App Service (Linux, Java SE 21) and Azure Database for PostgreSQL Flexible Server

All versions were verified on 2026-09-28 against start.spring.io and Maven Central. Package locations were verified by compiling a probe against Boot 4.1.1.

**Spec:** `docs/superpowers/specs/2026-09-28-stockroom-api-design.md`. Read it before any task: the plan implements it and explains its reasoning in terms of it.

## Global Constraints

- **Project root:** `C:\Users\OASIS\Documents\GitHub\stockroom-api`. Commands use Git Bash from that directory.
- **Java:** every shell session runs this first: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"`. Task 0 confirms the path. Java 18 is also installed and must **not** be used.
- **Base package:** `dev.wrenz.stockroom`. Group ID `dev.wrenz`, artifact `stockroom-api`, jar name `target/stockroom-api.jar`.
- **Money:** always `BigDecimal` / `NUMERIC(12,2)`; rounding is `RoundingMode.HALF_UP` at scale 2. No `double` or `float` for money anywhere.
- **Tenancy:** `shopId` comes **only** from `AuthUser.from(jwt)`. It is never read from a path, body or query parameter. Every repository method used by a service takes `shopId`.
- **Stock:** only `StockRepository` changes `stock_qty` after insert. `Product.stockQty` is mapped `updatable = false`.
- **Lock order:** any code that changes stock on several products does so in ascending product ID order.
- **Errors:** every error response is a `ProblemDetail` with a top-level `code`, using the codes from spec section 7: `VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `INSUFFICIENT_STOCK`, `ORDER_NOT_CANCELLABLE`, `DUPLICATE_SKU`, `EMAIL_TAKEN`, `CONCURRENT_UPDATE`, `RATE_LIMITED`, `INTERNAL_ERROR`.
- **Constraint names:** exactly as in `V1__init.sql` (Task 1). The error mapping depends on `uq_product_shop_sku` and `uq_app_user_email`.
- **Secrets:** never committed. `JWT_SECRET` must be at least 32 bytes, or the app refuses to start.
- **Tests:** run against real PostgreSQL, never H2. The test database is `stockroom_test`, reached with user and password `stockroom_test`. That user can't connect to any other database. `TestDatabaseGuard` refuses to migrate or truncate unless `current_database()` is exactly `stockroom_test`.
- **Numeric limits:** prices 0 to 999,999.99. Order totals are `NUMERIC(14,2)`. Stock can never exceed `StockRepository.MAX_STOCK` = 1,000,000,000. JSON decimals are **rejected** (400) for whole-number fields, never truncated.
- **Commits:** end with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Outward-facing steps are CHECKPOINTs.** The executor stops and asks Wrenz before running them: creating the public GitHub repo (Task 11), and creating any Azure resource (Task 13).

## Review Focus

These are the input classes most likely to bite a real user even though the spec never names them. Each one has a test in the task noted.

1. **Email with surrounding spaces or different capitalization** (`"  Owner@Shop.test "`) at register and login should just work, because the address is normalized to trimmed lowercase. Tested in Task 4 `AuthIntegrationTest.emailIsTrimmedAndCaseInsensitive`.
2. **Malformed JSON or wrong types** (`"quantity": "two"`, a truncated body) must return 400 `VALIDATION_FAILED`, never 500. Tested in Task 7 `OrderIntegrationTest.malformedBodyIs400`.
3. **A non-numeric ID in the path** (`/api/products/abc`) must return 400, not 500. Tested in Task 6 `ProductIntegrationTest.nonNumericIdIs400`.
4. **A price with more than 2 decimals, or too large** (`12.345`, `1e15`) must return 400 and must not be silently rounded. Tested in Task 6 `ProductIntegrationTest.invalidPricesAre400`.
5. **Search text containing `%` or `_`** must match literally, not act as a wildcard. Tested in Task 6 `ProductIntegrationTest.searchTreatsWildcardsLiterally`.

---

## File map

```
stockroom-api/
├── .gitattributes                         Task 1  (LF for mvnw, so CI on Linux can run it)
├── .gitignore                             Task 1  (Initializr default + .env)
├── pom.xml                                Task 1
├── mvnw, mvnw.cmd, .mvn/wrapper/…         Task 1  (from start.spring.io)
├── ops/reset-demo.sql                     Task 10
├── .github/workflows/ci.yml               Task 11
├── .github/workflows/deploy.yml           Task 13
├── README.md, STUDY_GUIDE.md              Task 12
└── src/
    ├── main/java/dev/wrenz/stockroom/
    │   ├── StockroomApiApplication.java   Task 1
    │   ├── common/                        Task 2  errors, paging, request logging (Task 4)
    │   ├── security/                      Task 4  JWT, security config, AuthUser; Task 5 rate limit
    │   ├── shop/                          Task 3  Shop entity + repository
    │   ├── user/                          Task 3  entity; Task 4 auth + users API
    │   ├── product/                       Task 3  entity; Task 6 API + service
    │   ├── stock/                         Task 3  StockRepository, movements
    │   ├── order/                         Task 3  entities; Task 7 rules, service, API
    │   ├── config/OpenApiConfig.java      Task 10
    │   └── demo/DemoDataSeeder.java       Task 10
    ├── main/resources/
    │   ├── application.yml                Task 1
    │   ├── application-prod.yml           Task 13
    │   └── db/migration/V1__init.sql      Task 1
    └── test/
        ├── resources/application-test.yml Task 1
        └── java/dev/wrenz/stockroom/…     one test class per task, listed in each task
```

Entities hold IDs as plain `Long` fields (`shopId`, `orderId`, `productId`), not JPA relationships. There is no lazy loading to explain, and the foreign keys live in SQL where the spec puts them.

---

### Task 0: Toolchain on this PC

No code is written in this task. It ends when Java 21 and a local PostgreSQL 16 with both databases are working.

**Files:** none.

- [ ] **Step 1: Install Temurin JDK 21**

Run: `winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements`

Then run: `ls "/c/Program Files/Eclipse Adoptium/"`

Expected: a folder named `jdk-21.0.12.101-hotspot`. If the version folder differs, update the `JAVA_HOME` line in Global Constraints to the real folder name before continuing.

- [ ] **Step 2: Verify Java 21 is the one in use**

Run: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; java -version`

Expected: `openjdk version "21.0.12"`.

- [ ] **Step 3: Install PostgreSQL 16 (Windows asks for admin permission; Wrenz approves it)**

Run: `winget install --id PostgreSQL.PostgreSQL.16 -e --accept-source-agreements --accept-package-agreements --override "--mode unattended --unattendedmodeui minimal --superpassword postgres --serverport 5432"`

Then run: `"/c/Program Files/PostgreSQL/16/bin/psql" --version`

Expected: `psql (PostgreSQL) 16.x`. The `postgres` superuser password is the local-only `postgres`. The installer's default `pg_hba.conf` accepts connections from localhost only.

- [ ] **Step 4: Create the app role and both databases**

```bash
export PGPASSWORD=postgres
PSQL="/c/Program Files/PostgreSQL/16/bin/psql"
"$PSQL" -U postgres -h localhost -c "CREATE ROLE stockroom LOGIN PASSWORD 'stockroom'"
"$PSQL" -U postgres -h localhost -c "CREATE ROLE stockroom_test LOGIN PASSWORD 'stockroom_test'"
"$PSQL" -U postgres -h localhost -c "CREATE DATABASE stockroom_dev OWNER stockroom"
"$PSQL" -U postgres -h localhost -c "CREATE DATABASE stockroom_test OWNER stockroom_test"
"$PSQL" -U postgres -h localhost -c "REVOKE CONNECT ON DATABASE stockroom_dev FROM PUBLIC"
"$PSQL" -U postgres -h localhost -c "REVOKE CONNECT ON DATABASE stockroom_test FROM PUBLIC"
"$PSQL" -U postgres -h localhost -c "GRANT CONNECT ON DATABASE stockroom_dev TO stockroom"
"$PSQL" -U postgres -h localhost -c "GRANT CONNECT ON DATABASE stockroom_test TO stockroom_test"
PGPASSWORD=stockroom_test "$PSQL" -U stockroom_test -h localhost -d stockroom_test -c "select current_user, current_database()"
PGPASSWORD=stockroom_test "$PSQL" -U stockroom_test -h localhost -d stockroom_dev -c "select 1"
```

Expected:
- The first query prints `stockroom_test | stockroom_test`.
- The last command **fails** with `permission denied for database "stockroom_dev"`. The test user can't touch the dev database even if a test is misconfigured.

---

### Task 1: Project scaffold, schema, and database backstop tests

**Files:**
- Create (from start.spring.io): `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`, `src/main/java/dev/wrenz/stockroom/StockroomApiApplication.java`
- Delete: `src/main/resources/application.properties`, `src/test/java/dev/wrenz/stockroom/StockroomApiApplicationTests.java`, `HELP.md`
- Create: `.gitattributes`, `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__init.sql`, `src/test/resources/application-test.yml`
- Test: `src/test/java/dev/wrenz/stockroom/support/TestDatabaseGuard.java`, `src/test/java/dev/wrenz/stockroom/support/TestDatabaseGuardTest.java`, `src/test/java/dev/wrenz/stockroom/support/IntegrationTest.java`, `src/test/java/dev/wrenz/stockroom/schema/SchemaConstraintsTest.java`

**Interfaces:**
- Produces:
  - the database schema
  - `TestDatabaseGuard`: a `@TestConfiguration` with a guarded `FlywayMigrationStrategy`, plus `static void requireTestDatabase(JdbcTemplate)`
  - the abstract `IntegrationTest` base class (fields `MockMvc mvc` and `JdbcTemplate jdbc`). It imports the guard, re-checks the database, and truncates all tables before each test. Every later integration test extends it.

- [ ] **Step 1: Generate the project into the repo (keeping the existing `docs/`)**

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
curl -s "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=4.1.1&javaVersion=21&groupId=dev.wrenz&artifactId=stockroom-api&name=stockroom-api&description=Multi-tenant%20inventory%20and%20orders%20API&packageName=dev.wrenz.stockroom&dependencies=web,data-jpa,security,oauth2-resource-server,validation,flyway,postgresql,actuator" -o /tmp/stockroom.zip
unzip -o -q /tmp/stockroom.zip -d .
rm -f HELP.md src/main/resources/application.properties src/test/java/dev/wrenz/stockroom/StockroomApiApplicationTests.java
ls
```

Expected: `docs  mvnw  mvnw.cmd  pom.xml  src` (plus hidden `.mvn`, `.gitignore`).

- [ ] **Step 2: Edit `pom.xml`**

Add these inside `<dependencies>`, directly after the opening tag:

```xml
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>3.1.1</version>
		</dependency>
		<dependency>
			<groupId>com.github.ben-manes.caffeine</groupId>
			<artifactId>caffeine</artifactId>
		</dependency>
```

Inside `<build>`, add `<finalName>stockroom-api</finalName>` as the first child. Then add this plugin inside `<build><plugins>`, after `spring-boot-maven-plugin`:

```xml
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.15</version>
				<executions>
					<execution>
						<id>prepare-agent</id>
						<goals><goal>prepare-agent</goal></goals>
					</execution>
					<execution>
						<id>report</id>
						<phase>verify</phase>
						<goals><goal>report</goal></goals>
					</execution>
				</executions>
			</plugin>
```

- [ ] **Step 3: Create `.gitattributes`, and add `.env` to `.gitignore`**

`.gitattributes`:

```
* text=auto
mvnw text eol=lf
*.sh text eol=lf
*.cmd text eol=crlf
```

Run: `printf '\n# Local secrets\n.env\n' >> .gitignore`

- [ ] **Step 4: Replace `StockroomApiApplication.java`**

```java
package dev.wrenz.stockroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockroomApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockroomApiApplication.class, args);
    }
}
```

- [ ] **Step 5: Create `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: stockroom-api
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/stockroom_dev}
    username: ${DB_USER:stockroom}
    password: ${DB_PASSWORD:stockroom}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  jackson:
    deserialization:
      fail-on-unknown-properties: false   # extra fields (e.g. stockQty on PUT) are ignored, never applied

server:
  port: 8080
  error:
    include-stacktrace: never
    include-message: never

stockroom:
  jwt:
    secret: ${JWT_SECRET:}
    ttl: 1h
  rate-limit:
    max-attempts-per-ip: 30
    max-failures-per-email: 5
    window: 15m
    max-keys: 10000

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
      show-components: never

logging:
  pattern:
    level: "%5p [%X{requestId:-}]"
```

- [ ] **Step 6: Create `src/test/resources/application-test.yml`**

```yaml
spring:
  datasource:
    url: ${TEST_DB_URL:jdbc:postgresql://localhost:5432/stockroom_test}
    username: ${TEST_DB_USER:stockroom_test}
    password: ${TEST_DB_PASSWORD:stockroom_test}
    hikari:
      # A stuck concurrency test fails fast instead of hanging the build or overlapping the next test.
      connection-init-sql: "SET statement_timeout = '30s'; SET lock_timeout = '15s'"

stockroom:
  jwt:
    secret: test-only-secret-that-is-comfortably-over-32-bytes
```

- [ ] **Step 7: Create `src/main/resources/db/migration/V1__init.sql`**

```sql
-- Stockroom API schema v1. See spec section 3.
-- Every tenant-owned table has UNIQUE (id, shop_id) so children can reference (id, shop_id):
-- the database itself then rejects a row that points at another shop's data.

CREATE TABLE shop (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120) NOT NULL CONSTRAINT ck_shop_name CHECK (char_length(name) BETWEEN 1 AND 120),
    -- Set only by DemoDataSeeder; no API can write it. The reset script trusts this flag, never names or emails.
    is_demo     BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_shop_single_demo ON shop (is_demo) WHERE is_demo;

CREATE TABLE app_user (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT       NOT NULL CONSTRAINT fk_app_user_shop REFERENCES shop (id),
    email          VARCHAR(254) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(10)  NOT NULL CONSTRAINT ck_app_user_role CHECK (role IN ('OWNER', 'STAFF')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_lower CHECK (email = lower(email)),
    CONSTRAINT uq_app_user_id_shop UNIQUE (id, shop_id)
);

CREATE TABLE product (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id              BIGINT        NOT NULL CONSTRAINT fk_product_shop REFERENCES shop (id),
    sku                  VARCHAR(64)   NOT NULL,
    name                 VARCHAR(200)  NOT NULL,
    price                NUMERIC(12,2) NOT NULL CONSTRAINT ck_product_price CHECK (price >= 0),
    stock_qty            INTEGER       NOT NULL CONSTRAINT ck_product_stock_nonneg CHECK (stock_qty >= 0),
    low_stock_threshold  INTEGER       NOT NULL DEFAULT 5 CONSTRAINT ck_product_threshold CHECK (low_stock_threshold >= 0),
    active               BOOLEAN       NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_shop_sku UNIQUE (shop_id, sku),
    CONSTRAINT uq_product_id_shop UNIQUE (id, shop_id)
);
CREATE INDEX ix_product_shop_active ON product (shop_id, active);

CREATE TABLE customer_order (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id       BIGINT        NOT NULL CONSTRAINT fk_order_shop REFERENCES shop (id),
    status        VARCHAR(10)   NOT NULL CONSTRAINT ck_order_status CHECK (status IN ('PLACED', 'CANCELLED')),
    -- 14 digits: the largest valid order (50 lines x 1,000 units x 999,999.99) is ~5 x 10^10.
    total         NUMERIC(14,2) NOT NULL DEFAULT 0 CONSTRAINT ck_order_total CHECK (total >= 0),
    created_by    BIGINT        NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    cancelled_at  TIMESTAMPTZ,
    CONSTRAINT uq_order_id_shop UNIQUE (id, shop_id),
    CONSTRAINT fk_order_created_by FOREIGN KEY (created_by, shop_id) REFERENCES app_user (id, shop_id)
);
CREATE INDEX ix_order_shop_created ON customer_order (shop_id, created_at DESC);

CREATE TABLE order_item (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT        NOT NULL,
    order_id    BIGINT        NOT NULL,
    product_id  BIGINT        NOT NULL,
    quantity    INTEGER       NOT NULL CONSTRAINT ck_order_item_qty CHECK (quantity > 0),
    unit_price  NUMERIC(12,2) NOT NULL CONSTRAINT ck_order_item_price CHECK (unit_price >= 0),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id, shop_id) REFERENCES customer_order (id, shop_id),
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id, shop_id) REFERENCES product (id, shop_id)
);
CREATE INDEX ix_order_item_order ON order_item (order_id);

-- Append-only stock ledger. SUM(change) per product equals product.stock_qty
-- (an application invariant kept by StockRepository, not enforced by the database).
CREATE TABLE stock_movement (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    shop_id     BIGINT       NOT NULL,
    product_id  BIGINT       NOT NULL,
    change      INTEGER      NOT NULL CONSTRAINT ck_movement_change_nonzero CHECK (change <> 0),
    reason      VARCHAR(20)  NOT NULL CONSTRAINT ck_movement_reason CHECK (reason IN ('OPENING_STOCK', 'RESTOCK', 'SALE', 'CANCEL')),
    order_id    BIGINT,
    note        VARCHAR(200),
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_movement_product FOREIGN KEY (product_id, shop_id) REFERENCES product (id, shop_id),
    CONSTRAINT fk_movement_order FOREIGN KEY (order_id, shop_id) REFERENCES customer_order (id, shop_id),
    CONSTRAINT fk_movement_created_by FOREIGN KEY (created_by, shop_id) REFERENCES app_user (id, shop_id)
);
CREATE INDEX ix_movement_product_created ON stock_movement (product_id, created_at DESC);
```

- [ ] **Step 8: Create the database guard and the test base class**

`src/test/java/dev/wrenz/stockroom/support/TestDatabaseGuard.java`:

```java
package dev.wrenz.stockroom.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Tests migrate and TRUNCATE the database. This guard makes sure that database is the disposable
 * test one, before Flyway runs and before every wipe. A misconfigured TEST_DB_URL fails loudly
 * instead of destroying real data.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseGuard {

    public static final String EXPECTED_DATABASE = "stockroom_test";

    @Bean
    FlywayMigrationStrategy guardedMigration(DataSource dataSource) {
        return flyway -> {
            requireTestDatabase(new JdbcTemplate(dataSource));
            flyway.migrate();
        };
    }

    public static void requireTestDatabase(JdbcTemplate jdbc) {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        if (!EXPECTED_DATABASE.equals(database)) {
            throw new IllegalStateException("Refusing to run destructive tests against database '" + database
                    + "'. Tests only run against '" + EXPECTED_DATABASE + "'.");
        }
    }
}
```

`src/test/java/dev/wrenz/stockroom/support/TestDatabaseGuardTest.java`:

```java
package dev.wrenz.stockroom.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDatabaseGuardTest {

    @Test
    void refusesAnyOtherDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("stockroom_dev");
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestDatabase(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stockroom_dev");
    }

    @Test
    void acceptsTheTestDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("stockroom_test");
        assertThatCode(() -> TestDatabaseGuard.requireTestDatabase(jdbc)).doesNotThrowAnyException();
    }
}
```

`src/test/java/dev/wrenz/stockroom/support/IntegrationTest.java`:

```java
package dev.wrenz.stockroom.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full application against the real PostgreSQL test database, wiped before every test. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        TestDatabaseGuard.requireTestDatabase(jdbc);
        jdbc.execute("TRUNCATE stock_movement, order_item, customer_order, product, app_user, shop RESTART IDENTITY CASCADE");
    }
}
```

- [ ] **Step 9: Write the failing schema test `src/test/java/dev/wrenz/stockroom/schema/SchemaConstraintsTest.java`**

```java
package dev.wrenz.stockroom.schema;

import dev.wrenz.stockroom.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database backstop from spec section 3: even if Java code has a bug, PostgreSQL rejects
 * rows that cross shops, negative stock, and duplicate SKUs.
 */
class SchemaConstraintsTest extends IntegrationTest {

    long shopA, shopB, userA, userB, productA, orderB;

    @BeforeEach
    void twoShops() {
        shopA = id("INSERT INTO shop (name) VALUES ('A') RETURNING id");
        shopB = id("INSERT INTO shop (name) VALUES ('B') RETURNING id");
        userA = id("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'a@a.test', 'x', 'OWNER') RETURNING id", shopA);
        userB = id("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'b@b.test', 'x', 'OWNER') RETURNING id", shopB);
        productA = id("INSERT INTO product (shop_id, sku, name, price, stock_qty) VALUES (?, 'SKU-1', 'Thing', 10.00, 5) RETURNING id", shopA);
        orderB = id("INSERT INTO customer_order (shop_id, status, created_by) VALUES (?, 'PLACED', ?) RETURNING id", shopB, userB);
    }

    @Test
    void flywayCreatedTheSchema() {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void orderLineCannotPointAtAnotherShopsProduct() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_item (shop_id, order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, 1, 10.00)",
                shopB, orderB, productA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_order_item_product");
    }

    @Test
    void stockMovementCannotPointAtAnotherShopsProduct() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stock_movement (shop_id, product_id, change, reason, created_by) VALUES (?, ?, 1, 'RESTOCK', ?)",
                shopB, productA, userB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_movement_product");
    }

    @Test
    void orderCannotBeCreatedByAUserFromAnotherShop() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO customer_order (shop_id, status, created_by) VALUES (?, 'PLACED', ?)", shopA, userB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_order_created_by");
    }

    @Test
    void stockCannotGoNegative() {
        assertThatThrownBy(() -> jdbc.update("UPDATE product SET stock_qty = -1 WHERE id = ?", productA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_product_stock_nonneg");
    }

    @Test
    void skuIsUniquePerShopOnly() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO product (shop_id, sku, name, price, stock_qty) VALUES (?, 'SKU-1', 'Dup', 1.00, 0)", shopA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_product_shop_sku");
        jdbc.update("INSERT INTO product (shop_id, sku, name, price, stock_qty) VALUES (?, 'SKU-1', 'Other shop', 1.00, 0)", shopB);
    }

    @Test
    void emailsMustBeStoredLowercase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'Mixed@Case.test', 'x', 'STAFF')", shopA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_app_user_email_lower");
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
```

- [ ] **Step 10: Run the tests**

Run: `./mvnw -B test -Dtest='SchemaConstraintsTest,TestDatabaseGuardTest'`

Expected: `Tests run: 9, Failures: 0, Errors: 0`. **Prove the guard works:** run `TEST_DB_URL=jdbc:postgresql://localhost:5432/stockroom_dev ./mvnw -B test -Dtest=SchemaConstraintsTest`. It must fail before touching anything, either with `permission denied` (the test user can't connect) or with the guard's `Refusing to run destructive tests` message.

If the context fails with "Failed to determine a suitable driver class" or "connection refused", PostgreSQL isn't running or `stockroom_test` doesn't exist: redo Task 0 Step 4. The test was written against a schema that is created in this same task, so the first successful run is the proof that the migration and its constraint names are right. To see the test fail once, rename `fk_order_item_product` in the SQL, re-run (`Failures: 1`), then revert it.

- [ ] **Step 11: Commit**

```bash
git add -A
git update-index --chmod=+x mvnw
git commit -m "feat: scaffold Spring Boot 4.1.1 project with tenant-safe schema

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git ls-files -s mvnw   # expect mode 100755
```

---

### Task 2: Error model and global exception handler

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/common/ApiException.java`, `NotFoundException.java`, `ConflictException.java`, `InsufficientStockException.java`, `UnauthorizedException.java`, `RateLimitedException.java`, `ValidationException.java`, `FieldErrorDto.java`, `PageResponse.java`, `GlobalExceptionHandler.java` (all in `common/`)
- Test: `src/test/java/dev/wrenz/stockroom/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `abstract class ApiException extends RuntimeException`: `HttpStatus getStatus()`, `String getCode()`, `Map<String,Object> getProperties()`
  - `new NotFoundException(String what, long id)` (message `"<what> <id> not found"`)
  - `new ConflictException(String code, String message)`
  - `new InsufficientStockException(long productId, int requested, int available)`
  - `new UnauthorizedException(String message)`
  - `new RateLimitedException()`
  - `new ValidationException(String field, String message)`
  - `record FieldErrorDto(String field, String message)`
  - `record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)` with `static <T> PageResponse<T> of(Page<T>)` and `static Pageable pageable(int page, int size)` (page ≥ 0; size clamped to 1..100)
  - `GlobalExceptionHandler` (a `@RestControllerAdvice`)

- [ ] **Step 1: Write the failing test `GlobalExceptionHandlerTest.java`**

```java
package dev.wrenz.stockroom.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    record Body(@NotBlank String name) {}

    @RestController
    static class ThrowingController {
        @GetMapping("/not-found") void notFound() { throw new NotFoundException("Product", 7); }
        @GetMapping("/stock") void stock() { throw new InsufficientStockException(7, 3, 1); }
        @GetMapping("/validation") void validation() { throw new ValidationException("items", "product 7 appears more than once"); }
        @GetMapping("/dup-sku") void dupSku() { throw integrity("uq_product_shop_sku"); }
        @GetMapping("/dup-email") void dupEmail() { throw integrity("uq_app_user_email"); }
        @GetMapping("/other-integrity") void otherIntegrity() { throw integrity("fk_order_item_product"); }
        @GetMapping("/deadlock") void deadlock() { throw new CannotAcquireLockException("deadlock detected"); }
        @GetMapping("/boom") void boom() { throw new IllegalStateException("secret internal detail"); }
        @GetMapping("/denied") void denied() { throw new AccessDeniedException("nope"); }
        @GetMapping("/unauth") void unauth() { throw new BadCredentialsException("bad"); }
        @GetMapping("/typed/{id}") void typed(@PathVariable long id) {}
        @PostMapping("/body") void body(@Valid @RequestBody Body body) {}

        private static DataIntegrityViolationException integrity(String constraint) {
            return new DataIntegrityViolationException("could not execute statement",
                    new ConstraintViolationException("violation", new SQLException("violation"), constraint));
        }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFound() throws Exception {
        mvc.perform(get("/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Product 7 not found"));
    }

    @Test
    void insufficientStockCarriesNumbers() throws Exception {
        mvc.perform(get("/stock")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.productId").value(7))
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.available").value(1));
    }

    @Test
    void validationExceptionListsTheField() throws Exception {
        mvc.perform(get("/validation")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("items"));
    }

    @Test
    void uniqueConstraintsAreMappedByName() throws Exception {
        mvc.perform(get("/dup-sku")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        mvc.perform(get("/dup-email")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void unknownIntegrityViolationIsA500NotAMisleading409() throws Exception {
        mvc.perform(get("/other-integrity")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void deadlockIsARetryable409() throws Exception {
        mvc.perform(get("/deadlock")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @Test
    void securityExceptionsThrownInControllers() throws Exception {
        mvc.perform(get("/denied")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/unauth")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void frameworkErrorsGetCodesToo() throws Exception {
        mvc.perform(get("/typed/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -B test -Dtest=GlobalExceptionHandlerTest`

Expected: COMPILATION ERROR (`NotFoundException`, `GlobalExceptionHandler` and the other classes don't exist yet).

- [ ] **Step 3: Implement the exception classes**

`ApiException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Base for every error we raise on purpose. The handler turns it into a ProblemDetail with this code. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    protected ApiException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getProperties() { return properties; }
}
```

`NotFoundException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Also used for other shops' data: 404 instead of 403 hides that the row exists. */
public class NotFoundException extends ApiException {
    public NotFoundException(String what, long id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found", Map.of());
    }
}
```

`ConflictException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message, Map.of());
    }
}
```

`InsufficientStockException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class InsufficientStockException extends ApiException {
    public InsufficientStockException(long productId, int requested, int available) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Not enough stock for product " + productId + ": requested " + requested + ", available " + available,
                Map.of("productId", productId, "requested", requested, "available", available));
    }
}
```

`UnauthorizedException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message, Map.of());
    }
}
```

`RateLimitedException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class RateLimitedException extends ApiException {
    public RateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Try again later.", Map.of());
    }
}
```

`FieldErrorDto.java`:

```java
package dev.wrenz.stockroom.common;

public record FieldErrorDto(String field, String message) {}
```

`ValidationException.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** A business-rule validation failure that Bean Validation can't express (e.g. duplicate order lines). */
public class ValidationException extends ApiException {
    public ValidationException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                Map.of("errors", List.of(new FieldErrorDto(field, message))));
    }
}
```

`PageResponse.java`:

```java
package dev.wrenz.stockroom.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** Our own stable JSON shape for pages, instead of serializing Spring's Page directly. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static final int MAX_SIZE = 100;

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Page numbers below 0 become 0; sizes are clamped to 1..100 (spec section 4). */
    public static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }
}
```

- [ ] **Step 4: Implement `GlobalExceptionHandler.java`**

```java
package dev.wrenz.stockroom.common;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns every exception into an RFC 9457 ProblemDetail with a top-level "code" (spec section 7).
 * Extending ResponseEntityExceptionHandler keeps Spring's correct statuses for framework errors
 * (bad JSON, wrong types, 404 routes, 405); handleExceptionInternal adds our code to those.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private record Conflict(String code, String message) {}

    /** Only these unique constraints are expected; any other integrity violation is a bug. */
    private static final Map<String, Conflict> KNOWN_CONSTRAINTS = Map.of(
            "uq_product_shop_sku", new Conflict("DUPLICATE_SKU", "A product with this SKU already exists in your shop"),
            "uq_app_user_email", new Conflict("EMAIL_TAKEN", "An account with this email already exists"));

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getCode(), ex.getMessage());
        ex.getProperties().forEach(pd::setProperty);
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "You don't have permission to do this");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        Conflict conflict = KNOWN_CONSTRAINTS.get(constraintName(ex));
        if (conflict != null) {
            return respond(HttpStatus.CONFLICT, conflict.code(), conflict.message());
        }
        log.error("Unexpected data integrity violation", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
    }

    /** PostgreSQL deadlocks and serialization failures: safe for the client to retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyFailureException ex) {
        log.warn("Concurrent update conflict: {}", ex.getMessage());
        return respond(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The data changed while saving. Please retry.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        List<FieldErrorDto> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDto(fe.getField(), fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            pd.setProperty("code", defaultCode(statusCode));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            default -> "HTTP_" + status.value();
        };
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(problem(status, code, detail));
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }

    /** Walks the cause chain to Hibernate's ConstraintViolationException and returns the constraint name. */
    @Nullable
    static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return cve.getConstraintName().replace("\"", "").toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./mvnw -B test -Dtest=GlobalExceptionHandlerTest`

Expected: `Tests run: 9, Failures: 0, Errors: 0`. If the Hibernate `ConstraintViolationException(String, SQLException, String)` constructor doesn't compile, check the constructors in Hibernate 7.4 (`javap -cp ~/.m2/repository/org/hibernate/orm/hibernate-core/7.4.5.Final/hibernate-core-7.4.5.Final.jar org.hibernate.exception.ConstraintViolationException`) and use the variant that takes `(String message, SQLException root, String sql, String constraintName)`, passing `null` for `sql`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: ProblemDetail error model with stable error codes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Entities, repositories and `StockRepository`

**Files:**
- Create: `shop/Shop.java`, `shop/ShopRepository.java`, `user/Role.java`, `user/AppUser.java`, `user/AppUserRepository.java`, `product/Product.java`, `product/ProductRepository.java`, `stock/MovementReason.java`, `stock/StockMovement.java`, `stock/StockMovementRepository.java`, `stock/StockRepository.java`, `order/OrderStatus.java`, `order/CustomerOrder.java`, `order/OrderItem.java`, `order/CustomerOrderRepository.java`, `order/OrderItemRepository.java` (all under `src/main/java/dev/wrenz/stockroom/`)
- Test: `src/test/java/dev/wrenz/stockroom/stock/StockRepositoryTest.java`, `src/test/java/dev/wrenz/stockroom/order/CustomerOrderTest.java`

**Interfaces:**
- Consumes: `ConflictException` (Task 2).
- Produces:
  - `Shop(String name)`, `static Shop demo(String name)`, `getId()`, `getName()`, `isDemo()`; `ShopRepository.existsByDemoTrue()`
  - `enum Role { OWNER, STAFF }`
  - `AppUser(long shopId, String email, String passwordHash, Role role)` with getters `getId()`, `getShopId()`, `getEmail()`, `getPasswordHash()`, `getRole()`, `getCreatedAt()`
  - `AppUserRepository`: `Optional<AppUser> findByEmail(String)`, `boolean existsByEmail(String)`, `List<AppUser> findByShopIdOrderById(long)`
  - `Product(long shopId, String sku, String name, BigDecimal price, int lowStockThreshold, int openingStock)`, `update(String sku, String name, BigDecimal price, int lowStockThreshold)`, `archive()`, `isLowStock()`, plus getters
  - `ProductRepository`: `Optional<Product> findByIdAndShopId(long id, long shopId)`, `List<Product> findByShopIdAndIdIn(long shopId, Collection<Long> ids)`, `Page<Product> search(long shopId, boolean includeArchived, boolean lowStockOnly, String pattern, Pageable pageable)`
  - `enum MovementReason { OPENING_STOCK, RESTOCK, SALE, CANCEL }`
  - `StockMovementRepository`: `Page<StockMovement> findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(long shopId, long productId, Pageable pageable)`
  - `StockRepository` (constant `public static final int MAX_STOCK = 1_000_000_000`):
    - `boolean deduct(long shopId, long productId, int quantity, long orderId, long userId)`
    - `boolean restock(long shopId, long productId, int quantity, String note, long userId)`: false if missing, archived, or it would exceed `MAX_STOCK`
    - `boolean returnStock(long shopId, long productId, int quantity, long orderId, long userId)`
    - `void recordOpeningStock(long shopId, long productId, int quantity, long userId)`
    - All four require an existing transaction (`Propagation.MANDATORY`).
  - `enum OrderStatus { PLACED, CANCELLED }`
  - `CustomerOrder.placed(long shopId, long userId)`, `cancel(Instant when)` (throws `ConflictException` with code `ORDER_NOT_CANCELLABLE`), `setTotal(BigDecimal)`, getters
  - `OrderItem(long shopId, long orderId, long productId, int quantity, BigDecimal unitPrice)`, getters
  - `CustomerOrderRepository`: `Optional<CustomerOrder> findByIdAndShopId(long, long)`, `Page<CustomerOrder> findByShopIdOrderByCreatedAtDescIdDesc(long, Pageable)`, `Optional<CustomerOrder> findForUpdate(long id, long shopId)` (row lock)
  - `OrderItemRepository`: `List<OrderItem> findByShopIdAndOrderIdOrderByProductId(long shopId, long orderId)`

- [ ] **Step 1: Write the failing unit test `CustomerOrderTest.java`**

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerOrderTest {

    @Test
    void newOrderIsPlacedWithZeroTotal() {
        CustomerOrder order = CustomerOrder.placed(1, 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getCancelledAt()).isNull();
    }

    @Test
    void placedOrderCanBeCancelledOnce() {
        CustomerOrder order = CustomerOrder.placed(1, 2);
        Instant when = Instant.parse("2026-09-28T10:00:00Z");
        order.cancel(when);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(when);

        assertThatThrownBy(() -> order.cancel(when))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("ORDER_NOT_CANCELLABLE");
    }
}
```

- [ ] **Step 2: Write the failing integration test `StockRepositoryTest.java`**

```java
package dev.wrenz.stockroom.stock;

import dev.wrenz.stockroom.order.CustomerOrder;
import dev.wrenz.stockroom.order.CustomerOrderRepository;
import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.shop.Shop;
import dev.wrenz.stockroom.shop.ShopRepository;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.AppUserRepository;
import dev.wrenz.stockroom.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockRepositoryTest extends IntegrationTest {

    @Autowired ShopRepository shops;
    @Autowired AppUserRepository users;
    @Autowired ProductRepository products;
    @Autowired CustomerOrderRepository orders;
    @Autowired StockRepository stock;
    @Autowired TransactionTemplate tx;

    long shopId, otherShopId, userId, productId, orderId;

    @BeforeEach
    void data() {
        shopId = shops.save(new Shop("Shop")).getId();
        otherShopId = shops.save(new Shop("Other")).getId();
        userId = users.save(new AppUser(shopId, "o@shop.test", "hash", Role.OWNER)).getId();
        productId = products.save(new Product(shopId, "SKU-1", "Rice", new BigDecimal("52.00"), 5, 5)).getId();
        orderId = orders.save(CustomerOrder.placed(shopId, userId)).getId();
    }

    @Test
    void deductRemovesStockAndRecordsASale() {
        boolean ok = tx.execute(s -> stock.deduct(shopId, productId, 2, orderId, userId));
        assertThat(ok).isTrue();
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT change FROM stock_movement WHERE reason = 'SALE' AND order_id = ?", Integer.class, orderId)).isEqualTo(-2);
    }

    @Test
    void deductRefusesWhenStockIsShortAndChangesNothing() {
        boolean ok = tx.execute(s -> stock.deduct(shopId, productId, 6, orderId, userId));
        assertThat(ok).isFalse();
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(movementCount()).isZero();
    }

    @Test
    void deductRefusesArchivedProductsAndOtherShops() {
        assertThat(tx.execute(s -> stock.deduct(otherShopId, productId, 1, orderId, userId))).isFalse();
        jdbc.update("UPDATE product SET active = false WHERE id = ?", productId);
        assertThat(tx.execute(s -> stock.deduct(shopId, productId, 1, orderId, userId))).isFalse();
        assertThat(stockOf(productId)).isEqualTo(5);
    }

    @Test
    void restockAddsStockWithNoteButNotForArchivedProducts() {
        assertThat(tx.execute(s -> stock.restock(shopId, productId, 10, "Supplier delivery", userId))).isTrue();
        assertThat(stockOf(productId)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT note FROM stock_movement WHERE reason = 'RESTOCK'", String.class)).isEqualTo("Supplier delivery");

        jdbc.update("UPDATE product SET active = false WHERE id = ?", productId);
        assertThat(tx.execute(s -> stock.restock(shopId, productId, 10, null, userId))).isFalse();
        assertThat(stockOf(productId)).isEqualTo(15);
    }

    @Test
    void restockRefusesToExceedTheStockLimit() {
        jdbc.update("UPDATE product SET stock_qty = ? WHERE id = ?", StockRepository.MAX_STOCK - 10, productId);
        assertThat(tx.execute(s -> stock.restock(shopId, productId, 11, null, userId))).isFalse();
        assertThat(tx.execute(s -> stock.restock(shopId, productId, 10, null, userId))).isTrue();
        assertThat(stockOf(productId)).isEqualTo(StockRepository.MAX_STOCK);
    }

    @Test
    void returnStockWorksEvenForArchivedProducts() {
        jdbc.update("UPDATE product SET active = false WHERE id = ?", productId);
        assertThat(tx.execute(s -> stock.returnStock(shopId, productId, 2, orderId, userId))).isTrue();
        assertThat(stockOf(productId)).isEqualTo(7);
    }

    @Test
    void recordOpeningStockOnlyWritesTheMovement() {
        tx.executeWithoutResult(s -> stock.recordOpeningStock(shopId, productId, 5, userId));
        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT change FROM stock_movement WHERE reason = 'OPENING_STOCK'", Integer.class)).isEqualTo(5);
        tx.executeWithoutResult(s -> stock.recordOpeningStock(shopId, productId, 0, userId));
        assertThat(movementCount()).isEqualTo(1);
    }

    /** Spec section 5, rule 1 and 2: a loaded or dirty Product entity must never overwrite stock. */
    @Test
    void staleProductEntityCannotOverwriteADeduction() {
        tx.executeWithoutResult(s -> {
            Product before = products.findByIdAndShopId(productId, shopId).orElseThrow();
            before.update(before.getSku(), "Renamed", before.getPrice(), before.getLowStockThreshold());
            assertThat(stock.deduct(shopId, productId, 2, orderId, userId)).isTrue();
            Product after = products.findByIdAndShopId(productId, shopId).orElseThrow();
            assertThat(after.getStockQty()).isEqualTo(3);
            after.update(after.getSku(), "Renamed twice", after.getPrice(), after.getLowStockThreshold());
        });
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, productId)).isEqualTo("Renamed twice");
    }

    @Test
    void stockChangesRequireATransaction() {
        assertThatThrownBy(() -> stock.deduct(shopId, productId, 1, orderId, userId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private int stockOf(long id) {
        return jdbc.queryForObject("SELECT stock_qty FROM product WHERE id = ?", Integer.class, id);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class);
    }
}
```

- [ ] **Step 3: Run both to confirm they fail**

Run: `./mvnw -B test -Dtest='CustomerOrderTest,StockRepositoryTest'`

Expected: COMPILATION ERROR (the entity classes don't exist yet).

- [ ] **Step 4: Implement shop and user entities**

`shop/Shop.java`:

```java
package dev.wrenz.stockroom.shop;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shop")
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Only DemoDataSeeder creates demo shops; no request can set this. */
    @Column(name = "is_demo", nullable = false, updatable = false)
    private boolean demo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Shop() {}

    public Shop(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }

    public static Shop demo(String name) {
        Shop shop = new Shop(name);
        shop.demo = true;
        return shop;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isDemo() { return demo; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`shop/ShopRepository.java`:

```java
package dev.wrenz.stockroom.shop;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopRepository extends JpaRepository<Shop, Long> {
    boolean existsByDemoTrue();
}
```

`user/Role.java`:

```java
package dev.wrenz.stockroom.user;

public enum Role { OWNER, STAFF }
```

`user/AppUser.java`:

```java
package dev.wrenz.stockroom.user;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {}

    /** email must already be trimmed and lowercase (the request records do that). */
    public AppUser(long shopId, String email, String passwordHash, Role role) {
        this.shopId = shopId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`user/AppUserRepository.java`:

```java
package dev.wrenz.stockroom.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);
    boolean existsByEmail(String email);
    List<AppUser> findByShopIdOrderById(long shopId);
}
```

- [ ] **Step 5: Implement the product entity and repository**

`product/Product.java`:

```java
package dev.wrenz.stockroom.product;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @DynamicUpdate: an UPDATE contains only the columns that actually changed. An edit (sku, name, price,
 * threshold) therefore can't write back a stale `active`, and an archive can't write back a stale name
 * or price, even when both loaded the row before the other committed.
 */
@Entity
@Table(name = "product")
@DynamicUpdate
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /**
     * Written once on insert, never by JPA afterwards (updatable = false). All later changes go
     * through StockRepository's guarded UPDATEs, so a stale entity can't overwrite a sale (spec section 5).
     */
    @Column(name = "stock_qty", nullable = false, insertable = true, updatable = false)
    private int stockQty;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {}

    public Product(long shopId, String sku, String name, BigDecimal price, int lowStockThreshold, int openingStock) {
        this.shopId = shopId;
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.lowStockThreshold = lowStockThreshold;
        this.stockQty = openingStock;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String sku, String name, BigDecimal price, int lowStockThreshold) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.lowStockThreshold = lowStockThreshold;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public boolean isLowStock() { return stockQty <= lowStockThreshold; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public int getStockQty() { return stockQty; }
    public int getLowStockThreshold() { return lowStockThreshold; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

`product/ProductRepository.java`:

```java
package dev.wrenz.stockroom.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndShopId(long id, long shopId);

    List<Product> findByShopIdAndIdIn(long shopId, Collection<Long> ids);

    /** pattern is a lowercase LIKE pattern with %, _ and \ already escaped (ProductService.likePattern). */
    @Query("""
            select p from Product p
            where p.shopId = :shopId
              and (:includeArchived = true or p.active = true)
              and (:lowStockOnly = false or p.stockQty <= p.lowStockThreshold)
              and (lower(p.name) like :pattern escape '\\' or lower(p.sku) like :pattern escape '\\')
            order by p.name, p.id
            """)
    Page<Product> search(@Param("shopId") long shopId,
                         @Param("includeArchived") boolean includeArchived,
                         @Param("lowStockOnly") boolean lowStockOnly,
                         @Param("pattern") String pattern,
                         Pageable pageable);
}
```

- [ ] **Step 6: Implement the stock ledger classes**

`stock/MovementReason.java`:

```java
package dev.wrenz.stockroom.stock;

public enum MovementReason { OPENING_STOCK, RESTOCK, SALE, CANCEL }
```

`stock/StockMovement.java`:

```java
package dev.wrenz.stockroom.stock;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Read-only view of the append-only ledger. Rows are inserted by StockRepository with SQL. */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "change")
    private int change;

    @Enumerated(EnumType.STRING)
    private MovementReason reason;

    @Column(name = "order_id")
    private Long orderId;

    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    protected StockMovement() {}

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public Long getProductId() { return productId; }
    public int getChange() { return change; }
    public MovementReason getReason() { return reason; }
    public Long getOrderId() { return orderId; }
    public String getNote() { return note; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`stock/StockMovementRepository.java`:

```java
package dev.wrenz.stockroom.stock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    Page<StockMovement> findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(long shopId, long productId, Pageable pageable);
}
```

`stock/StockRepository.java`:

```java
package dev.wrenz.stockroom.stock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY code that changes product.stock_qty after insert (spec section 5).
 *
 * Each change is one guarded SQL UPDATE: the check ("is there enough?") and the change happen in a
 * single atomic statement, and PostgreSQL's row lock makes concurrent orders wait their turn.
 * The matching stock_movement row is written in the same transaction, keeping the ledger in step.
 *
 * Before each UPDATE we flush pending JPA changes; after it we clear the persistence context, so no
 * stale Product entity survives in the transaction.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class StockRepository {

    /** Far below INTEGER's 2,147,483,647, leaving headroom for stock returned by cancellations. */
    public static final int MAX_STOCK = 1_000_000_000;

    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sale: succeeds only if the product is in this shop, active, and has enough stock. */
    public boolean deduct(long shopId, long productId, int quantity, long orderId, long userId) {
        return apply(shopId, productId, -quantity, MovementReason.SALE, orderId, null, userId, """
                UPDATE product SET stock_qty = stock_qty - ?, updated_at = now()
                WHERE id = ? AND shop_id = ? AND active = true AND stock_qty >= ?
                """, quantity, productId, shopId, quantity);
    }

    /** Restock: only active products in this shop, and never above MAX_STOCK. */
    public boolean restock(long shopId, long productId, int quantity, String note, long userId) {
        return apply(shopId, productId, quantity, MovementReason.RESTOCK, null, note, userId, """
                UPDATE product SET stock_qty = stock_qty + ?, updated_at = now()
                WHERE id = ? AND shop_id = ? AND active = true AND stock_qty <= ? - ?
                """, quantity, productId, shopId, MAX_STOCK, quantity);
    }

    /** Cancellation: reverses a sale, so archived products get their stock back too. */
    public boolean returnStock(long shopId, long productId, int quantity, long orderId, long userId) {
        return apply(shopId, productId, quantity, MovementReason.CANCEL, orderId, null, userId, """
                UPDATE product SET stock_qty = stock_qty + ?, updated_at = now()
                WHERE id = ? AND shop_id = ?
                """, quantity, productId, shopId);
    }

    /** The opening stock is set on insert; this writes the ledger row that explains it. */
    public void recordOpeningStock(long shopId, long productId, int quantity, long userId) {
        if (quantity > 0) {
            insertMovement(shopId, productId, quantity, MovementReason.OPENING_STOCK, null, null, userId);
        }
    }

    private boolean apply(long shopId, long productId, int change, MovementReason reason, Long orderId,
                          String note, long userId, String sql, Object... args) {
        entityManager.flush();
        int rows = jdbc.update(sql, args);
        if (rows == 1) {
            insertMovement(shopId, productId, change, reason, orderId, note, userId);
        }
        entityManager.clear();
        return rows == 1;
    }

    private void insertMovement(long shopId, long productId, int change, MovementReason reason, Long orderId,
                                String note, long userId) {
        jdbc.update("""
                INSERT INTO stock_movement (shop_id, product_id, change, reason, order_id, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, shopId, productId, change, reason.name(), orderId, note, userId);
    }
}
```

- [ ] **Step 7: Implement the order entities and repositories**

`order/OrderStatus.java`:

```java
package dev.wrenz.stockroom.order;

public enum OrderStatus { PLACED, CANCELLED }
```

`order/CustomerOrder.java`:

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ConflictException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Named CustomerOrder because ORDER is a reserved word in SQL. */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected CustomerOrder() {}

    public static CustomerOrder placed(long shopId, long userId) {
        CustomerOrder order = new CustomerOrder();
        order.shopId = shopId;
        order.createdBy = userId;
        order.status = OrderStatus.PLACED;
        order.total = BigDecimal.ZERO.setScale(2);
        order.createdAt = Instant.now();
        return order;
    }

    /** Only a PLACED order can be cancelled; this is the rule that stops stock being returned twice. */
    public void cancel(Instant when) {
        if (status != OrderStatus.PLACED) {
            throw new ConflictException("ORDER_NOT_CANCELLABLE", "Order " + id + " is already " + status);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = when;
    }

    public void setTotal(BigDecimal total) { this.total = total; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotal() { return total; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
```

`order/OrderItem.java`:

```java
package dev.wrenz.stockroom.order;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private Long shopId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    /** Price at the time of sale, so later price changes don't rewrite history. */
    @Column(name = "unit_price", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected OrderItem() {}

    public OrderItem(long shopId, long orderId, long productId, int quantity, BigDecimal unitPrice) {
        this.shopId = shopId;
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public Long getOrderId() { return orderId; }
    public Long getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
```

`order/CustomerOrderRepository.java`:

```java
package dev.wrenz.stockroom.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByIdAndShopId(long id, long shopId);

    Page<CustomerOrder> findByShopIdOrderByCreatedAtDescIdDesc(long shopId, Pageable pageable);

    /** SELECT ... FOR UPDATE: two concurrent cancels of one order run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id and o.shopId = :shopId")
    Optional<CustomerOrder> findForUpdate(@Param("id") long id, @Param("shopId") long shopId);
}
```

`order/OrderItemRepository.java`:

```java
package dev.wrenz.stockroom.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByShopIdAndOrderIdOrderByProductId(long shopId, long orderId);
}
```

- [ ] **Step 8: Run the tests**

Run: `./mvnw -B test -Dtest='CustomerOrderTest,StockRepositoryTest,SchemaConstraintsTest'`

Expected: `Tests run: 18, Failures: 0, Errors: 0`. `ddl-auto: validate` also checks every entity against the Flyway schema when the context starts; a mapping mistake fails the context load with a `SchemaManagementException` that names the column.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: entities and StockRepository, the single guarded writer of stock

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Security, authentication, and the users API

**Files:**
- Create in `security/`: `JwtProperties.java`, `JwtConfig.java`, `TokenService.java`, `AuthUser.java`, `SecurityConfig.java`, `ProblemSecurityHandlers.java`, `UserIdRequestAttributeFilter.java`, `Utf8MaxBytes.java`, `Utf8MaxBytesValidator.java`
- Create in `common/`: `RequestLoggingFilter.java`
- Create in `user/`: `RegisterRequest.java`, `LoginRequest.java`, `TokenResponse.java`, `CreateUserRequest.java`, `UserResponse.java`, `ReservedEmails.java`, `AuthService.java`, `AuthController.java`, `UserService.java`, `UserController.java`
- Test: `security/JwtConfigTest.java`, `security/Utf8MaxBytesValidatorTest.java`, `support/TestApi.java`, `user/AuthIntegrationTest.java`, `security/TokenCasesTest.java`, `common/HealthEndpointTest.java`

**Interfaces:**
- Consumes: `AppUser`, `AppUserRepository`, `Shop`, `ShopRepository`, `Role` (Task 3); the exceptions (Task 2).
- Produces:
  - `record AuthUser(long userId, long shopId, Role role)` with `static AuthUser from(Jwt jwt)` and `boolean isOwner()`
  - `TokenService.issue(AppUser) -> TokenResponse`
  - `record TokenResponse(String accessToken, String tokenType, long expiresIn, long userId, long shopId, Role role)`
  - `AuthService.register(RegisterRequest)` and `AuthService.login(LoginRequest)`. Task 5 changes both to take a second `String clientIp` parameter.
  - `PasswordEncoder` bean (BCrypt, strength 10)
  - Endpoints: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/users`, `GET /api/users`
  - Test helper `TestApi` with `record Session(String token, long userId, long shopId)`, `registerShop(name, email)`, `login(email, password)`, `createStaff(owner, email)`, and the constant `PASSWORD = "password123"`

- [ ] **Step 1: Write the failing unit tests**

`src/test/java/dev/wrenz/stockroom/security/JwtConfigTest.java`:

```java
package dev.wrenz.stockroom.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

    @Test
    void refusesMissingOrShortSecrets() {
        assertThatThrownBy(() -> JwtConfig.secretKey(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey("x".repeat(31))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsA32ByteSecret() {
        assertThat(JwtConfig.secretKey("x".repeat(32)).getAlgorithm()).isEqualTo("HmacSHA256");
    }
}
```

`src/test/java/dev/wrenz/stockroom/security/Utf8MaxBytesValidatorTest.java`:

```java
package dev.wrenz.stockroom.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8MaxBytesValidatorTest {

    private final Utf8MaxBytesValidator validator = new Utf8MaxBytesValidator(72);

    @Test
    void countsBytesNotCharacters() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("a".repeat(72), null)).isTrue();
        assertThat(validator.isValid("a".repeat(73), null)).isFalse();
        // "é" is 2 bytes in UTF-8: 40 characters = 80 bytes
        assertThat(validator.isValid("é".repeat(40), null)).isFalse();
        assertThat(validator.isValid("é".repeat(36), null)).isTrue();
    }
}
```

- [ ] **Step 2: Write the test helper `src/test/java/dev/wrenz/stockroom/support/TestApi.java`**

```java
package dev.wrenz.stockroom.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small HTTP helpers so tests read as scenarios. Every call goes through the real API. */
public final class TestApi {

    public static final String PASSWORD = "password123";

    public record Session(String token, long userId, long shopId) {
        public String bearer() { return "Bearer " + token; }
    }

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Session registerShop(String shopName, String email) throws Exception {
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"shopName":"%s","email":"%s","password":"%s"}
                        """.formatted(shopName, email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return session(json);
    }

    public Session login(String email, String password) throws Exception {
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return session(json);
    }

    public Session createStaff(Session owner, String email) throws Exception {
        mvc.perform(post("/api/users").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return login(email, PASSWORD);
    }

    private static Session session(String json) {
        return new Session(JsonPath.read(json, "$.accessToken"),
                ((Number) JsonPath.read(json, "$.userId")).longValue(),
                ((Number) JsonPath.read(json, "$.shopId")).longValue());
    }
}
```

Then add a field and one line to `IntegrationTest` (Task 1). Add the field `protected TestApi api;`, and at the end of `resetDatabase()` add `api = new TestApi(mvc);`.

- [ ] **Step 3: Write the failing integration tests**

`src/test/java/dev/wrenz/stockroom/user/AuthIntegrationTest.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIntegrationTest extends IntegrationTest {

    @Test
    void registerCreatesShopAndOwnerAndReturnsAToken() throws Exception {
        register("Aling Nena Store", "nena@shop.test", TestApi.PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.role").value("OWNER"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user", String.class)).startsWith("$2a$10$");
    }

    @Test
    void emailIsTrimmedAndCaseInsensitive() throws Exception {
        register("Shop", "  Owner@Shop.test ", TestApi.PASSWORD).andExpect(status().isCreated());
        api.login("owner@shop.test", TestApi.PASSWORD);
        login("  OWNER@shop.TEST", TestApi.PASSWORD).andExpect(status().isOk());
        register("Other", "owner@SHOP.test", TestApi.PASSWORD)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop", Integer.class)).isEqualTo(1);
    }

    @Test
    void passwordLimitIsCountedInBytes() throws Exception {
        register("A", "a@shop.test", "a".repeat(72)).andExpect(status().isCreated());
        register("B", "b@shop.test", "a".repeat(73)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        register("C", "c@shop.test", "é".repeat(40)).andExpect(status().isBadRequest());
        register("D", "d@shop.test", "short").andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetIdenticalResponses() throws Exception {
        api.registerShop("Shop", "owner@shop.test");
        String wrongPassword = login("owner@shop.test", "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@shop.test", "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void ownerAddsStaffWhoCanLogInAndBothAppearInTheList() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        assertThat(staff.shopId()).isEqualTo(owner.shopId());
        mvc.perform(get("/api/users").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].role").value("STAFF"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void theDemoEmailDomainIsReservedForTheSeeder() throws Exception {
        register("Demo Sari-Sari Store", "owner@demo.stockroom.dev", TestApi.PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(post("/api/users").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@DEMO.stockroom.dev\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email LIKE '%@demo.stockroom.dev'", Integer.class)).isZero();
    }

    @Test
    void staffEmailMustBeUnique() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(post("/api/users").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    private ResultActions register(String shop, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"shopName":"%s","email":"%s","password":"%s"}
                """.formatted(shop, email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password)));
    }
}
```

`src/test/java/dev/wrenz/stockroom/security/TokenCasesTest.java`:

```java
package dev.wrenz.stockroom.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The five token cases from spec section 6. Each checks the status AND the ProblemDetail shape. */
class TokenCasesTest extends IntegrationTest {

    @Autowired JwtEncoder realEncoder;

    @Test
    void missingToken() throws Exception {
        expectProblem(mvc.perform(get("/api/users")), 401, "UNAUTHORIZED");
    }

    @Test
    void malformedToken() throws Exception {
        expectProblem(mvc.perform(get("/api/users").header("Authorization", "Bearer not-a-jwt")), 401, "UNAUTHORIZED");
    }

    @Test
    void tokenSignedWithAnotherSecret() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "an-attacker-secret-also-over-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String forged = encode(attacker, owner, Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS));
        expectProblem(mvc.perform(get("/api/users").header("Authorization", "Bearer " + forged)), 401, "UNAUTHORIZED");
    }

    @Test
    void expiredToken() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = encode(realEncoder, owner, twoHoursAgo, twoHoursAgo.plus(1, ChronoUnit.HOURS));
        expectProblem(mvc.perform(get("/api/users").header("Authorization", "Bearer " + expired)), 401, "UNAUTHORIZED");
    }

    @Test
    void staffOnAnOwnerEndpoint() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        expectProblem(mvc.perform(post("/api/users").header("Authorization", staff.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@shop.test\",\"password\":\"password123\"}")), 403, "FORBIDDEN");
    }

    private static String encode(JwtEncoder encoder, TestApi.Session s, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(s.userId()))
                .issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("shopId", s.shopId()).claim("role", "OWNER")
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static void expectProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.code").value(code));
    }
}
```

`src/test/java/dev/wrenz/stockroom/common/HealthEndpointTest.java`:

```java
package dev.wrenz.stockroom.common;

import dev.wrenz.stockroom.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthEndpointTest extends IntegrationTest {

    @Test
    void healthIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }
}
```

- [ ] **Step 4: Run them to confirm they fail**

Run: `./mvnw -B test -Dtest='JwtConfigTest,Utf8MaxBytesValidatorTest,AuthIntegrationTest,TokenCasesTest,HealthEndpointTest'`

Expected: COMPILATION ERROR (`JwtConfig`, `Utf8MaxBytesValidator` and the other classes don't exist yet).

- [ ] **Step 5: Implement the password byte-limit validator**

`security/Utf8MaxBytes.java`:

```java
package dev.wrenz.stockroom.security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** BCrypt only reads the first 72 BYTES of a password. Reject longer ones instead of silently truncating. */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8MaxBytesValidator.class)
public @interface Utf8MaxBytes {
    int value();
    String message() default "must be at most {value} bytes when UTF-8 encoded";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

`security/Utf8MaxBytesValidator.java`:

```java
package dev.wrenz.stockroom.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class Utf8MaxBytesValidator implements ConstraintValidator<Utf8MaxBytes, String> {

    private int maxBytes;

    public Utf8MaxBytesValidator() {}

    Utf8MaxBytesValidator(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public void initialize(Utf8MaxBytes annotation) {
        this.maxBytes = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
```

- [ ] **Step 6: Implement JWT configuration and token issuing**

`security/JwtProperties.java`:

```java
package dev.wrenz.stockroom.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("stockroom.jwt")
public record JwtProperties(String secret, Duration ttl) {}
```

`security/JwtConfig.java`:

```java
package dev.wrenz.stockroom.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** HS256: one shared secret both signs and verifies. The app refuses to start with a weak secret. */
@Configuration
public class JwtConfig {

    static final int MIN_SECRET_BYTES = 32;

    static SecretKey secretKey(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "stockroom.jwt.secret (env JWT_SECRET) must be set and at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        return secretKey(properties.secret());
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
```

`user/TokenResponse.java`:

```java
package dev.wrenz.stockroom.user;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, long userId, long shopId, Role role) {}
```

`security/TokenService.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.TokenResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** Claims: sub = userId, shopId, role. Trusted until expiry: see the "stale claims" note in spec section 6. */
    public TokenResponse issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim("shopId", user.getShopId())
                .claim("role", user.getRole().name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", properties.ttl().toSeconds(), user.getId(), user.getShopId(), user.getRole());
    }
}
```

`security/AuthUser.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling. The ONLY place shopId comes from is the validated token (spec section 6):
 * never from the URL, body or query string.
 */
public record AuthUser(long userId, long shopId, Role role) {

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(
                Long.parseLong(jwt.getSubject()),
                ((Number) jwt.getClaim("shopId")).longValue(),
                Role.valueOf(jwt.getClaimAsString("role")));
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
```

- [ ] **Step 7: Implement the security filter chain and problem handlers**

`security/ProblemSecurityHandlers.java`:

```java
package dev.wrenz.stockroom.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security rejects requests in filters, before any controller runs, so @RestControllerAdvice
 * never sees them. We hand those exceptions to Spring MVC's exception resolver, which routes them to
 * GlobalExceptionHandler: one place builds every error body, so 401/403 have the same shape as the rest.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public ProblemSecurityHandlers(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) {
        resolver.resolveException(request, response, null, ex);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) {
        resolver.resolveException(request, response, null, ex);
    }
}
```

`common/RequestLoggingFilter.java`:

```java
package dev.wrenz.stockroom.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** One log line per request (method, path, status, duration, user) plus a request ID. Never bodies or tokens. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "stockroom.userId";
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} -> {} in {} ms user={}", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), ms, request.getAttribute(USER_ID_ATTRIBUTE));
            MDC.remove("requestId");
        }
    }
}
```

`security/UserIdRequestAttributeFilter.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.common.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Runs inside the security chain, after the token is checked, and records the user ID for the request log. Not a @Component, so it isn't registered twice. */
class UserIdRequestAttributeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            request.setAttribute(RequestLoggingFilter.USER_ID_ATTRIBUTE, jwt.getToken().getSubject());
        }
        chain.doFilter(request, response);
    }
}
```

`security/SecurityConfig.java`:

```java
package dev.wrenz.stockroom.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems) throws Exception {
        http
                // Stateless API: no session, no cookies, so no CSRF risk.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterAfter(new UserIdRequestAttributeFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /** Maps the token's "role" claim (OWNER/STAFF) to ROLE_OWNER/ROLE_STAFF for @PreAuthorize("hasRole(...)"). */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
```

- [ ] **Step 8: Implement the auth and user request/response records**

`user/RegisterRequest.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.Utf8MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record RegisterRequest(
        @NotBlank @Size(max = 120) String shopName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 8) @Utf8MaxBytes(72) String password) {

    /** Normalize before validation: trimmed shop name, trimmed lowercase email. */
    public RegisterRequest {
        shopName = shopName == null ? null : shopName.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
```

`user/LoginRequest.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.Utf8MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;

public record LoginRequest(@NotBlank String email, @NotNull @Utf8MaxBytes(72) String password) {

    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
```

`user/CreateUserRequest.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.Utf8MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 8) @Utf8MaxBytes(72) String password) {

    public CreateUserRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
```

`user/UserResponse.java`:

```java
package dev.wrenz.stockroom.user;

import java.time.Instant;

public record UserResponse(long id, String email, Role role, Instant createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
```

- [ ] **Step 9: Implement the services and controllers**

`user/ReservedEmails.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.common.ValidationException;

/** The demo accounts' domain can only be created by DemoDataSeeder, never through the API. */
public final class ReservedEmails {

    public static final String DEMO_DOMAIN = "@demo.stockroom.dev";

    private ReservedEmails() {}

    /** email is already trimmed and lowercased by the request record. */
    public static void requireNotReserved(String email) {
        if (email.endsWith(DEMO_DOMAIN)) {
            throw new ValidationException("email", "This email domain is reserved");
        }
    }
}
```

`user/AuthService.java`:

```java
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
```

`user/AuthController.java`:

```java
package dev.wrenz.stockroom.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }
}
```

`user/UserService.java`:

```java
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
```

`user/UserController.java`:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('OWNER')")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateUserRequest request) {
        return users.createStaff(AuthUser.from(jwt), request);
    }

    @GetMapping
    public List<UserResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return users.list(AuthUser.from(jwt));
    }
}
```

- [ ] **Step 10: Run the tests**

Run: `./mvnw -B test -Dtest='JwtConfigTest,Utf8MaxBytesValidatorTest,AuthIntegrationTest,TokenCasesTest,HealthEndpointTest'`

Expected: `Tests run: 17, Failures: 0, Errors: 0`. If `missingToken` returns an empty body, check that `exceptionHandling(...)` sets the entry point: the missing-token case comes from `ExceptionTranslationFilter`, not the resource server.

- [ ] **Step 11: Run the whole suite, then commit**

Run: `./mvnw -B test`. Expected: 0 failures.

```bash
git add -A
git commit -m "feat: JWT auth, owner/staff roles, security errors as ProblemDetail

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Login rate limiting

**Files:**
- Create: `security/RateLimitProperties.java`, `security/LoginRateLimiter.java`, `security/ClientIpResolver.java`
- Modify: `user/AuthService.java` (`register` and `login` take `String clientIp`), `user/AuthController.java` (pass `ClientIpResolver.resolve(request)`), `support/IntegrationTest.java` (clear the limiter before each test)
- Test: `security/ClientIpResolverTest.java`, `security/RateLimitTest.java`

**Interfaces:**
- Consumes: `AuthService`, `RateLimitedException`.
- Produces:
  - `LoginRateLimiter`: `void checkIp(String ip)`, `void checkEmailNotLocked(String email)`, `void recordFailure(String email)`, `void clear()`
  - `ClientIpResolver.resolve(HttpServletRequest, boolean trustForwardedFor) -> String`
  - `RateLimitProperties(int maxAttemptsPerIp, int maxFailuresPerEmail, Duration window, long maxKeys, boolean trustForwardedFor)`

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/wrenz/stockroom/security/ClientIpResolverTest.java`:

```java
package dev.wrenz.stockroom.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void withoutAHeaderUsesTheSocketAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("192.0.2.10");
    }

    @Test
    void theHeaderIsIgnoredUnlessWeAreBehindTheTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo("192.0.2.10");
    }

    @Test
    void usesTheLastEntryBecauseEarlierOnesCanBeForged() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("203.0.113.9");
    }

    @Test
    void stripsThePortAzureAppends() {
        MockHttpServletRequest ipv4 = new MockHttpServletRequest();
        ipv4.addHeader("X-Forwarded-For", "203.0.113.9:54321");
        assertThat(ClientIpResolver.resolve(ipv4, true)).isEqualTo("203.0.113.9");

        MockHttpServletRequest ipv6 = new MockHttpServletRequest();
        ipv6.addHeader("X-Forwarded-For", "[2001:db8::1]:443");
        assertThat(ClientIpResolver.resolve(ipv6, true)).isEqualTo("2001:db8::1");

        MockHttpServletRequest bareIpv6 = new MockHttpServletRequest();
        bareIpv6.addHeader("X-Forwarded-For", "2001:db8::1");
        assertThat(ClientIpResolver.resolve(bareIpv6, true)).isEqualTo("2001:db8::1");
    }
}
```

`src/test/java/dev/wrenz/stockroom/security/RateLimitTest.java`:

```java
package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Simulates production, where the app sits behind Azure's front end (the trusted proxy). */
@TestPropertySource(properties = "stockroom.rate-limit.trust-forwarded-for=true")
class RateLimitTest extends IntegrationTest {

    @Test
    void fiveFailuresLockThatEmailEvenForTheRightPassword() throws Exception {
        api.registerShop("Shop", "owner@shop.test");
        for (int i = 0; i < 5; i++) {
            login("owner@shop.test", "wrong-password", null).andExpect(status().isUnauthorized());
        }
        login("owner@shop.test", "wrong-password", null)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        login("owner@shop.test", TestApi.PASSWORD, null).andExpect(status().isTooManyRequests());
    }

    @Test
    void oneIpCannotSprayManyEmails() throws Exception {
        for (int i = 0; i < 30; i++) {
            login("user" + i + "@shop.test", "wrong-password", null).andExpect(status().isUnauthorized());
        }
        login("user31@shop.test", "wrong-password", null).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgedLeadingForwardedForValuesAreIgnored() throws Exception {
        for (int i = 0; i < 30; i++) {
            login("user" + i + "@shop.test", "wrong-password", "10.0.0." + i + ", 203.0.113.9")
                    .andExpect(status().isUnauthorized());
        }
        // The real client (last entry) is still 203.0.113.9, whatever the first entry claims.
        login("user31@shop.test", "wrong-password", "10.9.9.9, 203.0.113.9").andExpect(status().isTooManyRequests());
        // A different real client is unaffected.
        login("user32@shop.test", "wrong-password", "203.0.113.9, 198.51.100.7").andExpect(status().isUnauthorized());
    }

    private ResultActions login(String email, String password, String forwardedFor) throws Exception {
        var request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request);
    }
}
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./mvnw -B test -Dtest='ClientIpResolverTest,RateLimitTest'`

Expected: COMPILATION ERROR (`ClientIpResolver` doesn't exist).

- [ ] **Step 3: Implement the rate limiter**

`security/RateLimitProperties.java`:

```java
package dev.wrenz.stockroom.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * trustForwardedFor must be true ONLY when every request arrives through a proxy that appends the
 * client address (Azure App Service's front end). Otherwise any caller could pick their own "IP".
 */
@ConfigurationProperties("stockroom.rate-limit")
public record RateLimitProperties(int maxAttemptsPerIp, int maxFailuresPerEmail, Duration window, long maxKeys,
                                  boolean trustForwardedFor) {}
```

`security/ClientIpResolver.java`:

```java
package dev.wrenz.stockroom.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Behind Azure's front end (trustForwardedFor = true), the real client address is APPENDED to
 * X-Forwarded-For, sometimes with a port. Anything before it came from the client and can be forged,
 * so only the LAST entry is trusted. Without the trusted proxy, the header is ignored entirely.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request, boolean trustForwardedFor) {
        String header = trustForwardedFor ? request.getHeader("X-Forwarded-For") : null;
        if (header == null || header.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] entries = header.split(",");
        return stripPort(entries[entries.length - 1].trim());
    }

    static String stripPort(String value) {
        if (value.startsWith("[")) {                       // [2001:db8::1]:443
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        if (value.indexOf(':') == value.lastIndexOf(':') && value.indexOf(':') > 0) {  // 203.0.113.9:54321
            return value.substring(0, value.indexOf(':'));
        }
        return value;                                      // bare IPv4 or bare IPv6
    }
}
```

`security/LoginRateLimiter.java`:

```java
package dev.wrenz.stockroom.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.wrenz.stockroom.common.RateLimitedException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory, single-instance limits (spec section 6). Both caches are bounded and entries expire after
 * the window, counted from the first attempt. Several instances would need Redis instead (see README).
 */
@Component
public class LoginRateLimiter {

    private final Cache<String, AtomicInteger> attemptsByIp;
    private final Cache<String, AtomicInteger> failuresByEmail;
    private final int maxAttemptsPerIp;
    private final int maxFailuresPerEmail;

    public LoginRateLimiter(RateLimitProperties properties) {
        this.attemptsByIp = Caffeine.newBuilder().maximumSize(properties.maxKeys()).expireAfterWrite(properties.window()).build();
        this.failuresByEmail = Caffeine.newBuilder().maximumSize(properties.maxKeys()).expireAfterWrite(properties.window()).build();
        this.maxAttemptsPerIp = properties.maxAttemptsPerIp();
        this.maxFailuresPerEmail = properties.maxFailuresPerEmail();
    }

    /** Counts every login/register attempt from this IP, successful or not. */
    public void checkIp(String ip) {
        if (attemptsByIp.get(ip, key -> new AtomicInteger()).incrementAndGet() > maxAttemptsPerIp) {
            throw new RateLimitedException();
        }
    }

    public void checkEmailNotLocked(String email) {
        AtomicInteger failures = failuresByEmail.getIfPresent(email);
        if (failures != null && failures.get() >= maxFailuresPerEmail) {
            throw new RateLimitedException();
        }
    }

    public void recordFailure(String email) {
        failuresByEmail.get(email, key -> new AtomicInteger()).incrementAndGet();
    }

    /** For tests only: every test starts with empty counters. */
    public void clear() {
        attemptsByIp.invalidateAll();
        failuresByEmail.invalidateAll();
    }
}
```

- [ ] **Step 4: Wire the limiter into auth**

Replace the two public methods of `AuthService`. Add a `LoginRateLimiter limiter` constructor parameter and field (after `tokens`):

```java
    @Transactional
    public TokenResponse register(RegisterRequest request, String clientIp) {
        limiter.checkIp(clientIp);
        ReservedEmails.requireNotReserved(request.email());
        Shop shop = shops.save(new Shop(request.shopName()));
        AppUser owner = users.save(new AppUser(shop.getId(), request.email(), encoder.encode(request.password()), Role.OWNER));
        return tokens.issue(owner);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request, String clientIp) {
        limiter.checkIp(clientIp);
        limiter.checkEmailNotLocked(request.email());
        Optional<AppUser> user = users.findByEmail(request.email());
        boolean matches = encoder.matches(request.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            limiter.recordFailure(request.email());
            throw new UnauthorizedException("Invalid email or password");
        }
        return tokens.issue(user.get());
    }
```

The new constructor is:

```java
    public AuthService(ShopRepository shops, AppUserRepository users, PasswordEncoder encoder, TokenService tokens,
                       LoginRateLimiter limiter) {
        this.shops = shops;
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.limiter = limiter;
        this.dummyHash = encoder.encode("dummy-password-used-only-for-timing");
    }
```

Add the field `private final LoginRateLimiter limiter;` and the import `dev.wrenz.stockroom.security.LoginRateLimiter`.

Replace `AuthController` entirely:

```java
package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.security.ClientIpResolver;
import dev.wrenz.stockroom.security.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
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
```

In `src/main/resources/application.yml`, add `trust-forwarded-for: false` as the last line under `stockroom.rate-limit`. Production turns it on in Task 13.

In `IntegrationTest`, add `@Autowired protected LoginRateLimiter rateLimiter;` (import `dev.wrenz.stockroom.security.LoginRateLimiter`). In `resetDatabase()`, add `rateLimiter.clear();` after the TRUNCATE.

- [ ] **Step 5: Run the tests, then the full suite**

Run: `./mvnw -B test -Dtest='ClientIpResolverTest,RateLimitTest'`. Expected: `Tests run: 7, Failures: 0`.

Run: `./mvnw -B test`. Expected: 0 failures.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: bounded per-email and per-IP login rate limits

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Products, restock and stock history

**Files:**
- Create in `product/`: `ProductRequest.java`, `ProductUpdateRequest.java`, `ProductResponse.java`, `RestockRequest.java`, `ProductService.java`, `ProductController.java`
- Create in `stock/`: `MovementResponse.java`
- Create in `common/`: `JacksonConfig.java` (rejects decimals for whole-number fields)
- Modify: `support/TestApi.java` (add product helpers)
- Test: `product/ProductIntegrationTest.java`

**Interfaces:**
- Consumes: `Product`, `ProductRepository`, `StockRepository`, `StockMovementRepository` (Task 3); `AuthUser` (Task 4); `PageResponse`, `NotFoundException` (Task 2).
- Produces:
  - `ProductService`: `create(AuthUser, ProductRequest)`, `get(AuthUser, long)`, `list(AuthUser, boolean lowStock, String q, boolean includeArchived, int page, int size)`, `update(AuthUser, long, ProductUpdateRequest)`, `archive(AuthUser, long)`, `restock(AuthUser, long, RestockRequest)`, `movements(AuthUser, long, int page, int size)`
  - `static String ProductService.likePattern(String q)`
  - `TestApi.createProduct(Session, String sku, String price, int stock) -> long`, `TestApi.stockOf(Session, long productId) -> int`

- [ ] **Step 1: Add the product helpers to `TestApi`**

Add these imports: `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get`. Then add the methods:

```java
    public long createProduct(Session owner, String sku, String price, int stock) throws Exception {
        String json = mvc.perform(post("/api/products").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sku":"%s","name":"Product %s","price":%s,"initialStock":%d}
                                """.formatted(sku, sku, price, stock)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    public int stockOf(Session session, long productId) throws Exception {
        String json = mvc.perform(get("/api/products/" + productId).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.stockQty");
    }
```

- [ ] **Step 2: Write the failing test `src/test/java/dev/wrenz/stockroom/product/ProductIntegrationTest.java`**

```java
package dev.wrenz.stockroom.product;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductIntegrationTest extends IntegrationTest {

    TestApi.Session owner;
    TestApi.Session staff;

    @BeforeEach
    void shop() throws Exception {
        owner = api.registerShop("Shop", "owner@shop.test");
        staff = api.createStaff(owner, "staff@shop.test");
    }

    @Test
    void createRecordsOpeningStockInTheLedger() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"  RICE-KG \",\"name\":\" Rice \",\"price\":52.50,\"initialStock\":20}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("RICE-KG"))
                .andExpect(jsonPath("$.name").value("Rice"))
                .andExpect(jsonPath("$.price").value(52.5))
                .andExpect(jsonPath("$.stockQty").value(20))
                .andExpect(jsonPath("$.lowStockThreshold").value(5))
                .andExpect(jsonPath("$.lowStock").value(false))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT change FROM stock_movement WHERE reason = 'OPENING_STOCK'", Integer.class)).isEqualTo(20);
    }

    @Test
    void zeroOpeningStockWritesNoMovement() throws Exception {
        api.createProduct(owner, "EMPTY", "1.00", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isZero();
    }

    @Test
    void skuIsUniqueWithinAShopButNotAcrossShops() throws Exception {
        api.createProduct(owner, "SKU-1", "1.00", 0);
        create(owner, "{\"sku\":\"SKU-1\",\"name\":\"Dup\",\"price\":1.00}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        TestApi.Session other = api.registerShop("Other", "other@shop.test");
        api.createProduct(other, "SKU-1", "1.00", 0);
    }

    @Test
    void invalidPricesAre400() throws Exception {
        create(owner, "{\"sku\":\"A\",\"name\":\"A\",\"price\":12.345}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("price"));
        create(owner, "{\"sku\":\"B\",\"name\":\"B\",\"price\":-1}").andExpect(status().isBadRequest());
        create(owner, "{\"sku\":\"C\",\"name\":\"C\",\"price\":1e15}").andExpect(status().isBadRequest());
        create(owner, "{\"sku\":\"E\",\"name\":\"E\",\"price\":1000000.00}").andExpect(status().isBadRequest());
        create(owner, "{\"sku\":\"F\",\"name\":\"F\",\"price\":999999.99}").andExpect(status().isCreated());
        jdbc.update("DELETE FROM stock_movement"); jdbc.update("DELETE FROM product");
        create(owner, "{\"sku\":\"D\",\"name\":\"   \",\"price\":1.00}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class)).isZero();
    }

    @Test
    void fractionalWholeNumbersAre400NotTruncated() throws Exception {
        create(owner, "{\"sku\":\"A\",\"name\":\"A\",\"price\":1.00,\"initialStock\":1.5}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(owner, "{\"sku\":\"B\",\"name\":\"B\",\"price\":1.00,\"lowStockThreshold\":2.5}")
                .andExpect(status().isBadRequest());
        long id = api.createProduct(owner, "C", "1.00", 5);
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1.5}"))
                .andExpect(status().isBadRequest());
        assertThat(api.stockOf(owner, id)).isEqualTo(5);
    }

    @Test
    void restockBeyondTheStockLimitIs409() throws Exception {
        long id = api.createProduct(owner, "BIG", "1.00", 0);
        jdbc.update("UPDATE product SET stock_qty = ? WHERE id = ?", dev.wrenz.stockroom.stock.StockRepository.MAX_STOCK, id);
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));
    }

    @Test
    void updateNeverChangesStockEvenIfTheBodyTriesTo() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(put("/api/products/" + id).header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"New name\",\"price\":11.00,\"lowStockThreshold\":2,\"stockQty\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.stockQty").value(5));
    }

    @Test
    void updateToAnExistingSkuIs409() throws Exception {
        api.createProduct(owner, "SKU-1", "1.00", 0);
        long second = api.createProduct(owner, "SKU-2", "1.00", 0);
        mvc.perform(put("/api/products/" + second).header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"x\",\"price\":1.00,\"lowStockThreshold\":5}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    void restockAddsStockAndShowsInHistoryNewestFirst() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"note\":\"Delivery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQty").value(15));
        mvc.perform(get("/api/products/" + id + "/movements").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reason").value("RESTOCK"))
                .andExpect(jsonPath("$.content[0].note").value("Delivery"))
                .andExpect(jsonPath("$.content[1].reason").value("OPENING_STOCK"));
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archivedProductsAreHiddenAndCannotBeRestocked() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(delete("/api/products/" + id).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        list(owner, "").andExpect(jsonPath("$.totalElements").value(0));
        list(owner, "?includeArchived=true").andExpect(jsonPath("$.content[0].active").value(false));
        list(staff, "?includeArchived=true").andExpect(status().isForbidden());
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lowStockFilterAndSearch() throws Exception {
        api.createProduct(owner, "LOW-1", "1.00", 2);
        api.createProduct(owner, "FULL-1", "1.00", 50);
        list(staff, "?lowStock=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("LOW-1"))
                .andExpect(jsonPath("$.content[0].lowStock").value(true));
        list(staff, "?q=full").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        api.createProduct(owner, "A-1", "1.00", 1);
        list(owner, "?q=%25").andExpect(jsonPath("$.totalElements").value(0));   // "%"
        list(owner, "?q=_").andExpect(jsonPath("$.totalElements").value(0));
        api.createProduct(owner, "B_1", "1.00", 1);
        list(owner, "?q=_").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void pageSizeIsCappedAt100() throws Exception {
        list(owner, "?size=500").andExpect(jsonPath("$.size").value(100));
        list(owner, "?size=0&page=-3").andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/api/products/abc").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void staffCanReadButNotChangeProducts() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(get("/api/products/" + id).header("Authorization", staff.bearer())).andExpect(status().isOk());
        create(staff, "{\"sku\":\"X\",\"name\":\"X\",\"price\":1.00}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/" + id).header("Authorization", staff.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/products/" + id + "/movements").header("Authorization", staff.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/products/999").header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private ResultActions create(TestApi.Session s, String body) throws Exception {
        return mvc.perform(post("/api/products").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions list(TestApi.Session s, String query) throws Exception {
        return mvc.perform(get("/api/products" + query).header("Authorization", s.bearer()));
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `./mvnw -B test -Dtest=ProductIntegrationTest`

Expected: FAIL. `POST /api/products` returns 404 `NOT_FOUND` because the controller doesn't exist yet.

- [ ] **Step 4: Implement the request and response records**

`product/ProductRequest.java`:

```java
package dev.wrenz.stockroom.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 200) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal price,
        @Min(0) Integer lowStockThreshold,
        @Min(0) @Max(1_000_000) Integer initialStock) {

    public ProductRequest {
        sku = sku == null ? null : sku.trim();
        name = name == null ? null : name.trim();
    }

    public int thresholdOrDefault() { return lowStockThreshold == null ? 5 : lowStockThreshold; }
    public int initialStockOrZero() { return initialStock == null ? 0 : initialStock; }
}
```

`product/ProductUpdateRequest.java`:

```java
package dev.wrenz.stockroom.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** Deliberately has no stock field: stock only changes through restock and orders. */
public record ProductUpdateRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 200) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer lowStockThreshold) {

    public ProductUpdateRequest {
        sku = sku == null ? null : sku.trim();
        name = name == null ? null : name.trim();
    }
}
```

`product/RestockRequest.java`:

```java
package dev.wrenz.stockroom.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RestockRequest(@NotNull @Min(1) @Max(100_000) Integer quantity, @Size(max = 200) String note) {}
```

`product/ProductResponse.java`:

```java
package dev.wrenz.stockroom.product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(long id, String sku, String name, BigDecimal price, int stockQty, int lowStockThreshold,
                              boolean lowStock, boolean active, Instant createdAt, Instant updatedAt) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getPrice(), p.getStockQty(),
                p.getLowStockThreshold(), p.isLowStock(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
```

`stock/MovementResponse.java`:

```java
package dev.wrenz.stockroom.stock;

import java.time.Instant;

public record MovementResponse(long id, int change, MovementReason reason, Long orderId, String note,
                               long createdBy, Instant createdAt) {

    public static MovementResponse from(StockMovement m) {
        return new MovementResponse(m.getId(), m.getChange(), m.getReason(), m.getOrderId(), m.getNote(),
                m.getCreatedBy(), m.getCreatedAt());
    }
}
```

- [ ] **Step 5: Reject decimals for whole-number JSON fields: `common/JacksonConfig.java`**

By default, Jackson 3 turns `1.9` into `1` for an `Integer` field. This was verified against Boot 4.1.1 on 2026-09-28: `{"productId":2.7,"quantity":1.9}` became product 2, quantity 1. This makes it a 400 instead:

```java
package dev.wrenz.stockroom.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /** Quantities and IDs must be whole numbers: fail on 1.5 (and 1.0) instead of silently truncating. */
    @Bean
    JsonMapperBuilderCustomizer rejectDecimalsForWholeNumbers() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
```

- [ ] **Step 6: Implement `ProductService.java`**

```java
package dev.wrenz.stockroom.product;

import dev.wrenz.stockroom.common.ConflictException;
import dev.wrenz.stockroom.common.NotFoundException;
import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.stock.MovementResponse;
import dev.wrenz.stockroom.stock.StockMovementRepository;
import dev.wrenz.stockroom.stock.StockRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.Locale;

@Service
public class ProductService {

    private final ProductRepository products;
    private final StockRepository stock;
    private final StockMovementRepository movements;

    public ProductService(ProductRepository products, StockRepository stock, StockMovementRepository movements) {
        this.products = products;
        this.stock = stock;
        this.movements = movements;
    }

    @Transactional
    public ProductResponse create(AuthUser caller, ProductRequest request) {
        int opening = request.initialStockOrZero();
        Product product = products.saveAndFlush(new Product(caller.shopId(), request.sku(), request.name(),
                request.price().setScale(2, RoundingMode.UNNECESSARY), request.thresholdOrDefault(), opening));
        stock.recordOpeningStock(caller.shopId(), product.getId(), opening, caller.userId());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(AuthUser caller, long id) {
        return ProductResponse.from(find(caller, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(AuthUser caller, boolean lowStock, String q, boolean includeArchived,
                                              int page, int size) {
        if (includeArchived && !caller.isOwner()) {
            throw new AccessDeniedException("includeArchived is owner-only");
        }
        return PageResponse.of(products.search(caller.shopId(), includeArchived, lowStock, likePattern(q),
                PageResponse.pageable(page, size)).map(ProductResponse::from));
    }

    @Transactional
    public ProductResponse update(AuthUser caller, long id, ProductUpdateRequest request) {
        Product product = find(caller, id);
        product.update(request.sku(), request.name(), request.price().setScale(2, RoundingMode.UNNECESSARY),
                request.lowStockThreshold());
        return ProductResponse.from(products.saveAndFlush(product));
    }

    /** Archive, never delete: old orders and the stock history still point at this product. */
    @Transactional
    public void archive(AuthUser caller, long id) {
        Product product = find(caller, id);
        product.archive();
        products.saveAndFlush(product);
    }

    /** The guarded UPDATE matched nothing: missing/archived is 404; otherwise the stock limit was hit (409). */
    @Transactional
    public ProductResponse restock(AuthUser caller, long id, RestockRequest request) {
        if (!stock.restock(caller.shopId(), id, request.quantity(), request.note(), caller.userId())) {
            Product product = find(caller, id);
            if (!product.isActive()) {
                throw new NotFoundException("Product", id);
            }
            throw new ConflictException("STOCK_LIMIT_EXCEEDED",
                    "Restocking would take product " + id + " above " + StockRepository.MAX_STOCK + " units");
        }
        return ProductResponse.from(find(caller, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> movements(AuthUser caller, long id, int page, int size) {
        find(caller, id);
        return PageResponse.of(movements.findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(
                caller.shopId(), id, PageResponse.pageable(page, size)).map(MovementResponse::from));
    }

    private Product find(AuthUser caller, long id) {
        return products.findByIdAndShopId(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Product", id));
    }

    /** Lowercase "contains" pattern; %, _ and \ in the user's text match literally. */
    static String likePattern(String q) {
        String text = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
```

- [ ] **Step 7: Implement `ProductController.java`**

```java
package dev.wrenz.stockroom.product;

import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.stock.MovementResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    public PageResponse<ProductResponse> list(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(defaultValue = "false") boolean lowStock,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "false") boolean includeArchived,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return products.list(AuthUser.from(jwt), lowStock, q, includeArchived, page, size);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return products.get(AuthUser.from(jwt), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public ProductResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductRequest request) {
        return products.create(AuthUser.from(jwt), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ProductResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                  @Valid @RequestBody ProductUpdateRequest request) {
        return products.update(AuthUser.from(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        products.archive(AuthUser.from(jwt), id);
    }

    @PostMapping("/{id}/restock")
    @PreAuthorize("hasRole('OWNER')")
    public ProductResponse restock(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @Valid @RequestBody RestockRequest request) {
        return products.restock(AuthUser.from(jwt), id, request);
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasRole('OWNER')")
    public PageResponse<MovementResponse> movements(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return products.movements(AuthUser.from(jwt), id, page, size);
    }
}
```

- [ ] **Step 8: Run the test**

Run: `./mvnw -B test -Dtest=ProductIntegrationTest`

Expected: `Tests run: 16, Failures: 0, Errors: 0`. If `invalidPricesAre400` gets 500 for `1e15`, `@Digits(integer = 6)` isn't being applied: check that the import is `jakarta.validation.constraints.Digits`.

- [ ] **Step 9: Full suite, then commit**

Run: `./mvnw -B test`. Expected: 0 failures.

```bash
git add -A
git commit -m "feat: products, restock and stock history

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Orders: place, list, get, cancel

**Files:**
- Create in `order/`: `OrderLineRequest.java`, `PlaceOrderRequest.java`, `OrderLineResponse.java`, `OrderResponse.java`, `OrderSummaryResponse.java`, `OrderRules.java`, `OrderService.java`, `OrderController.java`
- Modify: `support/TestApi.java` (add `placeOrder`)
- Test: `order/OrderRulesTest.java`, `order/OrderIntegrationTest.java`

**Interfaces:**
- Consumes: `CustomerOrder`, `OrderItem`, the order repositories, `ProductRepository`, `StockRepository` (Task 3); `InsufficientStockException`, `NotFoundException`, `ValidationException` (Task 2); `AuthUser` (Task 4).
- Produces:
  - `record OrderLineRequest(Long productId, Integer quantity)`
  - `record PlaceOrderRequest(List<OrderLineRequest> items)`
  - `OrderService`: `place(AuthUser, PlaceOrderRequest) -> OrderResponse`, `get(AuthUser, long)`, `list(AuthUser, int page, int size) -> PageResponse<OrderSummaryResponse>`, `cancel(AuthUser, long) -> OrderResponse`
  - `OrderRules.sortedLines(List<OrderLineRequest>)`, `OrderRules.lineTotal(BigDecimal, int)`, `OrderRules.orderTotal(List<BigDecimal>)`
  - `TestApi.placeOrder(Session, long productId, int quantity) -> long`

- [ ] **Step 1: Write the failing unit test `OrderRulesTest.java`**

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRulesTest {

    @Test
    void linesAreSortedByProductIdSoLocksAreTakenInOneOrder() {
        List<OrderLineRequest> sorted = OrderRules.sortedLines(List.of(
                new OrderLineRequest(9L, 1), new OrderLineRequest(2L, 3), new OrderLineRequest(5L, 1)));
        assertThat(sorted).extracting(OrderLineRequest::productId).containsExactly(2L, 5L, 9L);
    }

    @Test
    void duplicateProductsAreRejectedNotMerged() {
        assertThatThrownBy(() -> OrderRules.sortedLines(List.of(new OrderLineRequest(2L, 1), new OrderLineRequest(2L, 4))))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
    }

    @Test
    void totalsUseExactDecimalArithmetic() {
        assertThat(OrderRules.lineTotal(new BigDecimal("0.10"), 3)).isEqualByComparingTo("0.30");
        assertThat(OrderRules.lineTotal(new BigDecimal("12.50"), 2)).isEqualTo(new BigDecimal("25.00"));
        assertThat(OrderRules.orderTotal(List.of(new BigDecimal("25.00"), new BigDecimal("3.00")))).isEqualTo(new BigDecimal("28.00"));
        assertThat(OrderRules.orderTotal(List.of())).isEqualTo(new BigDecimal("0.00"));
    }
}
```

- [ ] **Step 2: Add `placeOrder` to `TestApi`**

```java
    public long placeOrder(Session session, long productId, int quantity) throws Exception {
        String json = mvc.perform(post("/api/orders").header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"items":[{"productId":%d,"quantity":%d}]}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
```

- [ ] **Step 3: Write the failing test `src/test/java/dev/wrenz/stockroom/order/OrderIntegrationTest.java`**

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderIntegrationTest extends IntegrationTest {

    TestApi.Session owner;
    TestApi.Session staff;
    long noodles;   // 12.50, stock 10
    long soap;      // 3.00, stock 2

    @BeforeEach
    void shop() throws Exception {
        owner = api.registerShop("Shop", "owner@shop.test");
        staff = api.createStaff(owner, "staff@shop.test");
        noodles = api.createProduct(owner, "NOODLES", "12.50", 10);
        soap = api.createProduct(owner, "SOAP", "3.00", 2);
    }

    @Test
    void staffPlacesAMultiLineOrder() throws Exception {
        order(staff, "{\"items\":[{\"productId\":%d,\"quantity\":2},{\"productId\":%d,\"quantity\":1}]}".formatted(soap, noodles))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.total").value(18.5))
                .andExpect(jsonPath("$.createdBy").value((int) staff.userId()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value((int) noodles))
                .andExpect(jsonPath("$.items[0].lineTotal").value(12.5));
        assertThat(api.stockOf(owner, noodles)).isEqualTo(9);
        assertThat(api.stockOf(owner, soap)).isZero();
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'")).isEqualTo(2);
    }

    @Test
    void insufficientStockRejectsTheWholeOrderAndChangesNothing() throws Exception {
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":3}]}".formatted(noodles, soap))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.productId").value((int) soap))
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.available").value(2));
        assertThat(api.stockOf(owner, noodles)).isEqualTo(10);   // first line rolled back
        assertThat(count("SELECT count(*) FROM customer_order")).isZero();
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'")).isZero();
    }

    @Test
    void invalidOrdersAre400() throws Exception {
        order(owner, "{\"items\":[]}").andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":0}]}".formatted(noodles)).andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1001}]}".formatted(noodles)).andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]}".formatted(noodles, noodles))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("items"));
    }

    @Test
    void malformedBodyIs400() throws Exception {
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":\"two\"}]}".formatted(noodles))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        order(owner, "{\"items\":[").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1.9}]}".formatted(noodles)).andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d.7,\"quantity\":1}]}".formatted(noodles)).andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM customer_order")).isZero();
        assertThat(api.stockOf(owner, noodles)).isEqualTo(10);
    }

    @Test
    void nullItemIs400WithNoWrites() throws Exception {
        order(owner, "{\"items\":[null]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("SELECT count(*) FROM customer_order")).isZero();
    }

    @Test
    void theLargestAllowedOrderTotalFits() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            long id = api.createProduct(owner, "LUX-" + i, "999999.99", 1000);
            items.append(i == 0 ? "" : ",").append("{\"productId\":%d,\"quantity\":1000}".formatted(id));
        }
        order(owner, "{\"items\":[" + items + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(10_999_999_890.00));
    }

    @Test
    void unknownAndArchivedProductsAre404() throws Exception {
        order(owner, "{\"items\":[{\"productId\":999,\"quantity\":1}]}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/" + soap).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(soap))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void laterPriceChangesDoNotRewriteOldOrders() throws Exception {
        long orderId = api.placeOrder(owner, noodles, 1);
        mvc.perform(put("/api/products/" + noodles).header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"NOODLES\",\"name\":\"Noodles\",\"price\":99.00,\"lowStockThreshold\":5}")).andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.5))
                .andExpect(jsonPath("$.total").value(12.5));
    }

    @Test
    void listIsNewestFirst() throws Exception {
        long first = api.placeOrder(owner, noodles, 1);
        long second = api.placeOrder(owner, noodles, 1);
        mvc.perform(get("/api/orders").header("Authorization", staff.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value((int) second))
                .andExpect(jsonPath("$.content[1].id").value((int) first));
    }

    @Test
    void cancelReturnsStockOnceEvenForArchivedProducts() throws Exception {
        long orderId = api.placeOrder(owner, soap, 2);
        mvc.perform(delete("/api/products/" + soap).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());
        assertThat(count("SELECT stock_qty FROM product WHERE id = " + soap)).isEqualTo(2);
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        assertThat(count("SELECT stock_qty FROM product WHERE id = " + soap)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'CANCEL'")).isEqualTo(1);
    }

    @Test
    void staffCannotCancel() throws Exception {
        long orderId = api.placeOrder(owner, noodles, 1);
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", staff.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mvc.perform(get("/api/orders/999").header("Authorization", owner.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/999/cancel").header("Authorization", owner.bearer())).andExpect(status().isNotFound());
    }

    private ResultActions order(TestApi.Session s, String body) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
```

- [ ] **Step 4: Run both to confirm they fail**

Run: `./mvnw -B test -Dtest='OrderRulesTest,OrderIntegrationTest'`

Expected: COMPILATION ERROR (`OrderRules`, `OrderLineRequest` don't exist).

- [ ] **Step 5: Implement the order records and rules**

`order/OrderLineRequest.java`:

```java
package dev.wrenz.stockroom.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderLineRequest(@NotNull Long productId, @NotNull @Min(1) @Max(1000) Integer quantity) {}
```

`order/PlaceOrderRequest.java`:

```java
package dev.wrenz.stockroom.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** @NotNull on the element too: {"items":[null]} must be a 400, not a NullPointerException. */
public record PlaceOrderRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid OrderLineRequest> items) {}
```

`order/OrderLineResponse.java`:

```java
package dev.wrenz.stockroom.order;

import java.math.BigDecimal;

public record OrderLineResponse(long productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    public static OrderLineResponse from(OrderItem item) {
        return new OrderLineResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice(),
                OrderRules.lineTotal(item.getUnitPrice(), item.getQuantity()));
    }
}
```

`order/OrderResponse.java`:

```java
package dev.wrenz.stockroom.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(long id, OrderStatus status, BigDecimal total, long createdBy, Instant createdAt,
                            Instant cancelledAt, List<OrderLineResponse> items) {

    public static OrderResponse from(CustomerOrder order, List<OrderItem> items) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotal(), order.getCreatedBy(),
                order.getCreatedAt(), order.getCancelledAt(), items.stream().map(OrderLineResponse::from).toList());
    }
}
```

`order/OrderSummaryResponse.java`:

```java
package dev.wrenz.stockroom.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(long id, OrderStatus status, BigDecimal total, long createdBy, Instant createdAt,
                                   Instant cancelledAt) {

    public static OrderSummaryResponse from(CustomerOrder o) {
        return new OrderSummaryResponse(o.getId(), o.getStatus(), o.getTotal(), o.getCreatedBy(), o.getCreatedAt(),
                o.getCancelledAt());
    }
}
```

`order/OrderRules.java`:

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure order rules, no database: easy to unit test and to explain. */
public final class OrderRules {

    private OrderRules() {}

    /**
     * Rejects duplicate products (400 rather than silently merging), then sorts by product ID.
     * Every order locks product rows in the same ascending order, so two orders can't deadlock
     * waiting on each other (spec section 5).
     */
    public static List<OrderLineRequest> sortedLines(List<OrderLineRequest> lines) {
        Set<Long> seen = new HashSet<>();
        for (OrderLineRequest line : lines) {
            if (!seen.add(line.productId())) {
                throw new ValidationException("items", "product " + line.productId() + " appears more than once");
            }
        }
        return lines.stream().sorted(Comparator.comparing(OrderLineRequest::productId)).toList();
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal orderTotal(List<BigDecimal> lineTotals) {
        return lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 6: Implement `OrderService.java`**

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.InsufficientStockException;
import dev.wrenz.stockroom.common.NotFoundException;
import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.stock.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final CustomerOrderRepository orders;
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final StockRepository stock;

    public OrderService(CustomerOrderRepository orders, OrderItemRepository orderItems, ProductRepository products,
                        StockRepository stock) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.products = products;
        this.stock = stock;
    }

    /**
     * One transaction (spec section 5): insert the order, deduct each line with a guarded UPDATE in
     * product ID order, then price the lines from the now-locked rows. Any failure rolls back everything.
     */
    @Transactional
    public OrderResponse place(AuthUser caller, PlaceOrderRequest request) {
        List<OrderLineRequest> lines = OrderRules.sortedLines(request.items());
        long orderId = orders.saveAndFlush(CustomerOrder.placed(caller.shopId(), caller.userId())).getId();

        for (OrderLineRequest line : lines) {
            if (!stock.deduct(caller.shopId(), line.productId(), line.quantity(), orderId, caller.userId())) {
                throw stockFailure(caller, line);
            }
        }

        Map<Long, Product> byId = products.findByShopIdAndIdIn(caller.shopId(),
                        lines.stream().map(OrderLineRequest::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<OrderItem> items = new ArrayList<>();
        List<BigDecimal> lineTotals = new ArrayList<>();
        for (OrderLineRequest line : lines) {
            BigDecimal unitPrice = byId.get(line.productId()).getPrice();
            items.add(new OrderItem(caller.shopId(), orderId, line.productId(), line.quantity(), unitPrice));
            lineTotals.add(OrderRules.lineTotal(unitPrice, line.quantity()));
        }
        orderItems.saveAll(items);

        CustomerOrder order = orders.findByIdAndShopId(orderId, caller.shopId()).orElseThrow();
        order.setTotal(OrderRules.orderTotal(lineTotals));
        return OrderResponse.from(order, items);
    }

    /** The deduction matched no row: find out why, so the client gets 404 or a precise 409. */
    private RuntimeException stockFailure(AuthUser caller, OrderLineRequest line) {
        return products.findByIdAndShopId(line.productId(), caller.shopId())
                .filter(Product::isActive)
                .<RuntimeException>map(p -> new InsufficientStockException(p.getId(), line.quantity(), p.getStockQty()))
                .orElseGet(() -> new NotFoundException("Product", line.productId()));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(AuthUser caller, long id) {
        CustomerOrder order = orders.findByIdAndShopId(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Order", id));
        return OrderResponse.from(order, orderItems.findByShopIdAndOrderIdOrderByProductId(caller.shopId(), id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> list(AuthUser caller, int page, int size) {
        return PageResponse.of(orders.findByShopIdOrderByCreatedAtDescIdDesc(caller.shopId(), PageResponse.pageable(page, size))
                .map(OrderSummaryResponse::from));
    }

    /**
     * Lock the order row first (a second cancel waits, then sees CANCELLED and gets 409), mark it
     * cancelled, then return stock in ascending product ID order: the same lock order as place().
     */
    @Transactional
    public OrderResponse cancel(AuthUser caller, long id) {
        CustomerOrder order = orders.findForUpdate(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Order", id));
        order.cancel(Instant.now());
        orders.saveAndFlush(order);

        List<OrderItem> items = orderItems.findByShopIdAndOrderIdOrderByProductId(caller.shopId(), id);
        for (OrderItem item : items) {
            if (!stock.returnStock(caller.shopId(), item.getProductId(), item.getQuantity(), id, caller.userId())) {
                throw new IllegalStateException("Order " + id + " references missing product " + item.getProductId());
            }
        }
        return get(caller, id);
    }
}
```

- [ ] **Step 7: Implement `OrderController.java`**

```java
package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse place(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaceOrderRequest request) {
        return orders.place(AuthUser.from(jwt), request);
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return orders.list(AuthUser.from(jwt), page, size);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return orders.get(AuthUser.from(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OWNER')")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return orders.cancel(AuthUser.from(jwt), id);
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `./mvnw -B test -Dtest='OrderRulesTest,OrderIntegrationTest'`

Expected: `Tests run: 15, Failures: 0, Errors: 0`. If `listIsNewestFirst` is flaky, both orders got the same `created_at`, and the `id DESC` tie-breaker in `findByShopIdOrderByCreatedAtDescIdDesc` should have resolved it: check that the method name has both parts.

- [ ] **Step 9: Full suite, then commit**

Run: `./mvnw -B test`. Expected: 0 failures.

```bash
git add -A
git commit -m "feat: atomic order placement and cancellation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Concurrency and ledger proofs

These tests are the evidence for the core promise in spec section 1. They run on real threads against real PostgreSQL.

**Files:**
- Test: `src/test/java/dev/wrenz/stockroom/concurrency/ConcurrencyTest.java`

**Interfaces:**
- Consumes: `OrderService.place/cancel`, `ProductService.archive/update/restock`, `ProductUpdateRequest`, `ProductRepository`, `AuthUser`, `Role`, `TestApi`.

- [ ] **Step 1: Write the test**

```java
package dev.wrenz.stockroom.concurrency;

import dev.wrenz.stockroom.common.ConflictException;
import dev.wrenz.stockroom.common.InsufficientStockException;
import dev.wrenz.stockroom.common.NotFoundException;
import dev.wrenz.stockroom.order.OrderLineRequest;
import dev.wrenz.stockroom.order.OrderService;
import dev.wrenz.stockroom.order.PlaceOrderRequest;
import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.product.ProductService;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import dev.wrenz.stockroom.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends IntegrationTest {

    @Autowired OrderService orders;
    @Autowired ProductService productService;
    @Autowired ProductRepository products;
    @Autowired TransactionTemplate tx;

    TestApi.Session session;
    AuthUser owner;
    ExecutorService pool;

    @BeforeEach
    void shop() throws Exception {
        session = api.registerShop("Shop", "owner@shop.test");
        owner = new AuthUser(session.userId(), session.shopId(), Role.OWNER);
        pool = Executors.newFixedThreadPool(20);
    }

    /** Make sure no worker transaction outlives this test and overlaps the next test's TRUNCATE. */
    @AfterEach
    void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(45, TimeUnit.SECONDS))
                .as("worker threads still running; the next test's cleanup would race them").isTrue();
    }

    @Test
    void twentyBuyersForFiveUnitsSellExactlyFive() throws Exception {
        long pid = api.createProduct(session, "HOT-ITEM", "10.00", 5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    orders.place(owner, order(line(pid, 1)));
                    return "SOLD";
                } catch (InsufficientStockException e) {
                    return "REJECTED";
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));

        assertThat(outcomes).filteredOn("SOLD"::equals).hasSize(5);
        assertThat(outcomes).filteredOn("REJECTED"::equals).hasSize(15);
        assertThat(stockOf(pid)).isZero();
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM customer_order")).isEqualTo(5);
        assertLedgerBalanced();
    }

    @Test
    void oppositeLineOrderAndCancellationsDoNotDeadlock() throws Exception {
        long a = api.createProduct(session, "A", "1.00", 10_000);
        long b = api.createProduct(session, "B", "1.00", 10_000);
        // Orders to cancel exist BEFORE the race starts, so the canceller always has real work to overlap.
        List<Long> toCancel = new ArrayList<>();
        for (int i = 0; i < 50; i++) toCancel.add(orders.place(owner, order(line(a, 1), line(b, 1))).id());

        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> ab = () -> { start.await(); for (int i = 0; i < 100; i++) orders.place(owner, order(line(a, 1), line(b, 1))); return null; };
        Callable<Void> ba = () -> { start.await(); for (int i = 0; i < 100; i++) orders.place(owner, order(line(b, 1), line(a, 1))); return null; };
        Callable<Void> canceller = () -> { start.await(); for (long id : toCancel) orders.cancel(owner, id); return null; };
        List<Future<Void>> futures = List.of(pool.submit(ab), pool.submit(ba), pool.submit(canceller));
        start.countDown();
        for (Future<Void> f : futures) {
            f.get(120, TimeUnit.SECONDS);   // rethrows any deadlock (surfacing as a ConcurrencyFailureException)
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE status = 'CANCELLED'", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE status = 'PLACED'", Integer.class)).isEqualTo(200);
        assertThat(stockOf(a)).isEqualTo(10_000 - 200);
        assertThat(stockOf(b)).isEqualTo(10_000 - 200);
        assertLedgerBalanced();
    }

    @Test
    void twoSimultaneousCancelsReturnStockExactlyOnce() throws Exception {
        long pid = api.createProduct(session, "CANCEL-ME", "1.00", 10);
        for (int round = 0; round < 10; round++) {
            long orderId = orders.place(owner, order(line(pid, 3))).id();
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> cancel = () -> {
                start.await();
                try {
                    orders.cancel(owner, orderId);
                    return "CANCELLED";
                } catch (ConflictException alreadyCancelled) {
                    return "CONFLICT";
                }
            };
            Future<String> first = pool.submit(cancel);
            Future<String> second = pool.submit(cancel);
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CANCELLED", "CONFLICT");
            assertThat(stockOf(pid)).isEqualTo(10);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE reason = 'CANCEL'", Integer.class)).isEqualTo(10);
        assertLedgerBalanced();
    }

    /** Spec section 5 plus @DynamicUpdate: an edit that loaded the row before an archive must not un-archive it. */
    @Test
    void editRacingAnArchiveKeepsTheProductArchived() throws Exception {
        long pid = api.createProduct(session, "EDIT-VS-ARCHIVE", "10.00", 5);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch archived = new CountDownLatch(1);
        Future<?> edit = pool.submit(() -> tx.executeWithoutResult(status -> {
            Product p = products.findByIdAndShopId(pid, session.shopId()).orElseThrow();   // sees active = true
            loaded.countDown();
            await(archived);
            p.update(p.getSku(), "Edited after archive", p.getPrice(), p.getLowStockThreshold());
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        productService.archive(owner, pid);
        archived.countDown();
        edit.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id = ?", Boolean.class, pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid)).isEqualTo("Edited after archive");
    }

    @Test
    void archiveRacingAnEditKeepsTheNewName() throws Exception {
        long pid = api.createProduct(session, "ARCHIVE-VS-EDIT", "10.00", 5);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch edited = new CountDownLatch(1);
        Future<?> archive = pool.submit(() -> tx.executeWithoutResult(status -> {
            Product p = products.findByIdAndShopId(pid, session.shopId()).orElseThrow();   // sees the old name
            loaded.countDown();
            await(edited);
            p.archive();
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        productService.update(owner, pid, new dev.wrenz.stockroom.product.ProductUpdateRequest(
                "ARCHIVE-VS-EDIT", "New name", new java.math.BigDecimal("12.00"), 5));
        edited.countDown();
        archive.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id = ?", Boolean.class, pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid)).isEqualTo("New name");
        assertThat(jdbc.queryForObject("SELECT price FROM product WHERE id = ?", java.math.BigDecimal.class, pid)).isEqualByComparingTo("12.00");
    }

    @Test
    void archiveRacingAnOrderHasOnlyTheTwoDefinedOutcomes() throws Exception {
        for (int i = 0; i < 20; i++) {
            long pid = api.createProduct(session, "RACE-" + i, "5.00", 5);
            CountDownLatch start = new CountDownLatch(1);
            Future<String> order = pool.submit(() -> {
                start.await();
                try {
                    orders.place(owner, order(line(pid, 1)));
                    return "ORDERED";
                } catch (NotFoundException e) {
                    return "REJECTED";
                }
            });
            Future<?> archive = pool.submit(() -> { start.await(); productService.archive(owner, pid); return null; });
            start.countDown();
            String outcome = order.get(30, TimeUnit.SECONDS);
            archive.get(30, TimeUnit.SECONDS);

            assertThat(jdbc.queryForObject("SELECT active FROM product WHERE id = ?", Boolean.class, pid)).isFalse();
            assertThat(stockOf(pid)).isEqualTo(outcome.equals("ORDERED") ? 4 : 5);
        }
        assertLedgerBalanced();
    }

    /** Spec section 5: a PUT that loaded the product before an order committed must not erase the sale. */
    @Test
    void productEditRacingAnOrderKeepsTheSale() throws Exception {
        long pid = api.createProduct(session, "EDITED", "10.00", 5);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch ordered = new CountDownLatch(1);
        Future<?> edit = pool.submit(() -> tx.executeWithoutResult(status -> {
            Product p = products.findByIdAndShopId(pid, session.shopId()).orElseThrow();
            loaded.countDown();
            await(ordered);
            p.update(p.getSku(), "Renamed while an order ran", p.getPrice(), p.getLowStockThreshold());
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        orders.place(owner, order(line(pid, 2)));
        ordered.countDown();
        edit.get(30, TimeUnit.SECONDS);

        assertThat(stockOf(pid)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, pid)).isEqualTo("Renamed while an order ran");
        assertLedgerBalanced();
    }

    @Test
    void ledgerMatchesStockAfterMixedOperations() throws Exception {
        long pid = api.createProduct(session, "MIXED", "2.00", 10);
        productService.restock(owner, pid, new dev.wrenz.stockroom.product.RestockRequest(5, "delivery"));
        long o1 = orders.place(owner, order(line(pid, 3))).id();
        orders.place(owner, order(line(pid, 4)));
        orders.cancel(owner, o1);
        assertThat(stockOf(pid)).isEqualTo(11);
        assertLedgerBalanced();
    }

    private void assertLedgerBalanced() {
        List<Long> mismatched = jdbc.queryForList("""
                SELECT p.id FROM product p
                LEFT JOIN (SELECT product_id, SUM(change) AS total FROM stock_movement GROUP BY product_id) m
                       ON m.product_id = p.id
                WHERE p.stock_qty <> COALESCE(m.total, 0)
                """, Long.class);
        assertThat(mismatched).as("products whose stock differs from their ledger").isEmpty();
    }

    private static PlaceOrderRequest order(OrderLineRequest... lines) {
        return new PlaceOrderRequest(List.of(lines));
    }

    private static OrderLineRequest line(long productId, int quantity) {
        return new OrderLineRequest(productId, quantity);
    }

    private int stockOf(long pid) {
        return jdbc.queryForObject("SELECT stock_qty FROM product WHERE id = ?", Integer.class, pid);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw -B test -Dtest=ConcurrencyTest`

Expected: `Tests run: 8, Failures: 0, Errors: 0`. These tests pass on first run because the behavior they check was built in Tasks 3 and 7. **Prove they can fail** by breaking each protection on purpose, one at a time, and reverting each:
- In `StockRepository.deduct`, delete `AND stock_qty >= ?` (and its last argument). `twentyBuyersForFiveUnitsSellExactlyFive` must fail. It fails either with `ck_product_stock_nonneg` (the database backstop) or with a wrong count.
- In `Product`, change `updatable = false` to `true` on `stockQty`. `productEditRacingAnOrderKeepsTheSale` must fail with stock 5 instead of 3.
- In `Product`, remove `@DynamicUpdate`. `editRacingAnArchiveKeepsTheProductArchived` must fail (active comes back `true`), and `archiveRacingAnEditKeepsTheNewName` must fail (the old name comes back).
- In `CustomerOrderRepository.findForUpdate`, remove `@Lock(LockModeType.PESSIMISTIC_WRITE)`. `twoSimultaneousCancelsReturnStockExactlyOnce` should fail with stock returned twice. It's a race, so run it up to 3 times; a single pass without the lock doesn't disprove it.

Revert all of them before committing, and re-run: 8/8 pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: concurrency proofs for overselling, lock order, archive race, stale edits

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Cross-shop isolation tests

**Files:**
- Test: `src/test/java/dev/wrenz/stockroom/isolation/CrossShopIsolationTest.java`

**Interfaces:**
- Consumes: `TestApi` (`registerShop`, `createStaff`, `createProduct`, `placeOrder`).

- [ ] **Step 1: Write the test**

```java
package dev.wrenz.stockroom.isolation;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec section 8: shop B tries to read and change shop A's data. Owners get 404 everywhere;
 * staff get 404 where staff are allowed and 403 on owner-only endpoints (the role check runs
 * before any lookup, so 403 reveals nothing). Shop A must be untouched afterwards.
 */
class CrossShopIsolationTest extends IntegrationTest {

    TestApi.Session ownerA, ownerB, staffB;
    long productA, orderA;
    String shopABefore;

    @BeforeEach
    void twoShops() throws Exception {
        ownerA = api.registerShop("Shop A", "a@shop.test");
        ownerB = api.registerShop("Shop B", "b@shop.test");
        staffB = api.createStaff(ownerB, "staff@b.test");
        productA = api.createProduct(ownerA, "A-1", "10.00", 5);
        orderA = api.placeOrder(ownerA, productA, 1);
        shopABefore = shopAState();
    }

    @AfterEach
    void shopAIsUntouched() {
        assertThat(shopAState()).isEqualTo(shopABefore);
    }

    // One test per operation, so a leak in any single path fails its own test (and @AfterEach
    // reports any change to shop A) instead of being hidden behind an earlier assertion.

    @Test
    void ownerBCannotReadAProduct() throws Exception {
        notFound(get("/api/products/" + productA), ownerB);
    }

    @Test
    void ownerBCannotEditAProduct() throws Exception {
        notFound(put("/api/products/" + productA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"HACK\",\"name\":\"Hacked\",\"price\":0.00,\"lowStockThreshold\":0}"), ownerB);
    }

    @Test
    void ownerBCannotArchiveAProduct() throws Exception {
        notFound(delete("/api/products/" + productA), ownerB);
    }

    @Test
    void ownerBCannotRestockAProduct() throws Exception {
        notFound(post("/api/products/" + productA + "/restock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":100}"), ownerB);
    }

    @Test
    void ownerBCannotReadStockHistory() throws Exception {
        notFound(get("/api/products/" + productA + "/movements"), ownerB);
    }

    @Test
    void ownerBCannotOrderAnotherShopsProduct() throws Exception {
        notFound(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(productA)), ownerB);
    }

    @Test
    void ownerBCannotReadAnOrder() throws Exception {
        notFound(get("/api/orders/" + orderA), ownerB);
    }

    @Test
    void ownerBCannotCancelAnOrder() throws Exception {
        notFound(post("/api/orders/" + orderA + "/cancel"), ownerB);
    }

    @Test
    void staffOfShopBGet404WhereStaffMayGoAnd403Elsewhere() throws Exception {
        notFound(get("/api/products/" + productA), staffB);
        notFound(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(productA)), staffB);
        notFound(get("/api/orders/" + orderA), staffB);
        forbidden(delete("/api/products/" + productA), staffB);
        forbidden(post("/api/products/" + productA + "/restock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":100}"), staffB);
        forbidden(post("/api/orders/" + orderA + "/cancel"), staffB);
    }

    @Test
    void listsOnlyShowYourOwnShop() throws Exception {
        mvc.perform(get("/api/products").header("Authorization", ownerB.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/orders").header("Authorization", ownerB.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/users").header("Authorization", ownerB.bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void notFound(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          TestApi.Session as) throws Exception {
        expect(mvc.perform(request.header("Authorization", as.bearer())), 404, "NOT_FOUND");
    }

    private void forbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           TestApi.Session as) throws Exception {
        expect(mvc.perform(request.header("Authorization", as.bearer())), 403, "FORBIDDEN");
    }

    private static void expect(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private String shopAState() {
        return jdbc.queryForObject("""
                SELECT p.stock_qty || '|' || p.name || '|' || p.active || '|' || p.price
                       || '|' || (SELECT count(*) FROM stock_movement WHERE shop_id = p.shop_id)
                       || '|' || (SELECT string_agg(status, ',' ORDER BY id) FROM customer_order WHERE shop_id = p.shop_id)
                FROM product p WHERE p.id = ?
                """, String.class, productA);
    }
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw -B test -Dtest=CrossShopIsolationTest`

Expected: `Tests run: 10, Failures: 0, Errors: 0`. **Prove each kind of check can fail**, one break at a time, reverting each:
- **Read path:** in `ProductService.find`, replace `products.findByIdAndShopId(id, caller.shopId())` with `products.findById(id)`. `ownerBCannotReadAProduct` must fail (200 instead of 404).
- **Write path:** in `StockRepository.restock`, remove `AND shop_id = ?` from the SQL (and the matching `shopId` argument). The UPDATE now really does change shop A's stock: `ProductService.restock` calls `find` only after a failed update, so this break reaches the write. `ownerBCannotRestockAProduct` must fail with **500 instead of 404**. The stock movement row (shop B, product A) is rejected by the composite foreign key, the transaction rolls back, and `@AfterEach` confirms shop A is unchanged. That's two layers: the application filter failed, and the database backstop from Task 1 caught it.
- **Order path:** in `StockRepository.deduct`, remove `AND shop_id = ?`. `ownerBCannotOrderAnotherShopsProduct` must fail the same way (500 from the foreign key, shop A unchanged).
- **Without the backstop:** to watch a leak actually change shop A, also drop `fk_movement_product` in the test database (`ALTER TABLE stock_movement DROP CONSTRAINT fk_movement_product`) together with the restock break. Now `@AfterEach` reports shop A's stock changed. Afterwards, recreate the test database (as the `postgres` user: `DROP DATABASE stockroom_test;`, then the `CREATE DATABASE … OWNER stockroom_test`, `REVOKE` and `GRANT` lines from Task 0 Step 4). The next test run migrates it fresh.

Revert all of them and re-run: 10/10 pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: cross-shop isolation for reads and writes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Swagger UI, demo data and the demo reset script

**Files:**
- Create: `src/main/java/dev/wrenz/stockroom/config/OpenApiConfig.java`, `src/main/java/dev/wrenz/stockroom/demo/DemoDataSeeder.java`, `ops/reset-demo.sql`
- Test: `config/OpenApiTest.java`, `demo/DemoDataSeederTest.java`

**Interfaces:**
- Consumes: the repositories, `StockRepository`, `PasswordEncoder`.
- Produces: `DemoDataSeeder` (`@Profile("demo")`) with public constants `SHOP_NAME`, `OWNER_EMAIL`, `OWNER_PASSWORD`, `STAFF_EMAIL`, `STAFF_PASSWORD`. The demo shop is identified **only** by `shop.is_demo = true`, which no API can set. The emails use the `@demo.stockroom.dev` domain, which `ReservedEmails` blocks from sign-up (Task 4).

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/wrenz/stockroom/config/OpenApiTest.java`:

```java
package dev.wrenz.stockroom.config;

import dev.wrenz.stockroom.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OpenApiTest extends IntegrationTest {

    @Test
    void apiDocsArePublicAndDescribeBearerAuth() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Stockroom API"))
                .andExpect(jsonPath("$.paths['/api/orders']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
```

`src/test/java/dev/wrenz/stockroom/demo/DemoDataSeederTest.java`:

```java
package dev.wrenz.stockroom.demo;

import dev.wrenz.stockroom.support.TestDatabaseGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles({"test", "demo"})
@Import(TestDatabaseGuard.class)
class DemoDataSeederTest {

    @Autowired DemoDataSeeder seeder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void clean() {
        TestDatabaseGuard.requireTestDatabase(jdbc);
        jdbc.execute("TRUNCATE stock_movement, order_item, customer_order, product, app_user, shop RESTART IDENTITY CASCADE");
    }

    /** Leave nothing behind: a stray demo-domain user would make the next demo-profile startup fail closed. */
    @AfterEach
    void cleanUp() {
        clean();
    }

    @Test
    void seedsOnceAndNeverOverwritesWhatVisitorsChanged() throws Exception {
        seeder.run(null);
        assertThat(count("SELECT count(*) FROM product")).isEqualTo(15);
        assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(2);
        String ownerHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, DemoDataSeeder.OWNER_EMAIL);
        assertThat(encoder.matches(DemoDataSeeder.OWNER_PASSWORD, ownerHash)).isTrue();

        jdbc.update("UPDATE product SET name = 'Changed by a visitor' WHERE sku = 'RICE-KG'");
        seeder.run(null);

        assertThat(count("SELECT count(*) FROM product")).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE sku = 'RICE-KG'", String.class)).isEqualTo("Changed by a visitor");
    }

    @Test
    void openingStockIsInTheLedger() throws Exception {
        seeder.run(null);
        assertThat(jdbc.queryForList("""
                SELECT p.id FROM product p
                LEFT JOIN (SELECT product_id, SUM(change) AS total FROM stock_movement GROUP BY product_id) m ON m.product_id = p.id
                WHERE p.stock_qty <> COALESCE(m.total, 0)
                """, Long.class)).isEmpty();
    }

    @Test
    void resetDeletesOnlyTheFlaggedDemoShopEvenIfAnotherHasTheSameName() throws Exception {
        seeder.run(null);
        // A real customer who happens to use the same shop name must never be touched.
        long sameName = jdbc.queryForObject("INSERT INTO shop (name) VALUES (?) RETURNING id", Long.class, DemoDataSeeder.SHOP_NAME);
        jdbc.update("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, 'real@shop.test', 'x', 'OWNER')", sameName);
        String script = Files.readString(Path.of("ops/reset-demo.sql"));

        jdbc.execute(script);

        assertThat(count("SELECT count(*) FROM product")).isZero();
        assertThat(count("SELECT count(*) FROM shop")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_user WHERE email = 'real@shop.test'")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.execute(script)).hasMessageContaining("Demo shop not found");
    }

    @Test
    void seederFailsClosedIfADemoEmailIsHeldByANonDemoShop() {
        long impostor = jdbc.queryForObject("INSERT INTO shop (name) VALUES (?) RETURNING id", Long.class, DemoDataSeeder.SHOP_NAME);
        jdbc.update("INSERT INTO app_user (shop_id, email, password_hash, role) VALUES (?, ?, 'x', 'OWNER')", impostor, DemoDataSeeder.OWNER_EMAIL);

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DemoDataSeeder.OWNER_EMAIL);
        assertThat(count("SELECT count(*) FROM shop WHERE is_demo")).isZero();
        assertThat(count("SELECT count(*) FROM product")).isZero();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./mvnw -B test -Dtest='OpenApiTest,DemoDataSeederTest'`

Expected: COMPILATION ERROR (`DemoDataSeeder` doesn't exist).

- [ ] **Step 3: Implement `OpenApiConfig.java`**

```java
package dev.wrenz.stockroom.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Swagger UI is an interactive demo: log in with a demo account, then "Authorize" with the token. */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Stockroom API", version = "1.0",
                description = "Multi-tenant inventory and orders for small shops. Stock can't go negative, even with concurrent orders."),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
```

- [ ] **Step 4: Implement `DemoDataSeeder.java`**

```java
package dev.wrenz.stockroom.demo;

import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.shop.Shop;
import dev.wrenz.stockroom.shop.ShopRepository;
import dev.wrenz.stockroom.stock.StockRepository;
import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.AppUserRepository;
import dev.wrenz.stockroom.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds the public demo shop ONCE (spec section 8). If a shop flagged is_demo already exists it does
 * nothing: no reset, no password change, so a redeploy never disrupts someone using the demo.
 * To start over, run ops/reset-demo.sql by hand, then restart the app.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String SHOP_NAME = "Demo Sari-Sari Store";
    public static final String OWNER_EMAIL = "owner@demo.stockroom.dev";
    public static final String OWNER_PASSWORD = "demo-owner-2026";
    public static final String STAFF_EMAIL = "staff@demo.stockroom.dev";
    public static final String STAFF_PASSWORD = "demo-staff-2026";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Item(String sku, String name, String price, int stock, int threshold) {}

    private static final List<Item> ITEMS = List.of(
            new Item("PANCIT-CANTON", "Instant Pancit Canton 60g", "16.00", 120, 24),
            new Item("COFFEE-3IN1", "3-in-1 Coffee Sachet 28g", "9.00", 200, 40),
            new Item("MILK-33G", "Powdered Milk 33g", "14.00", 80, 20),
            new Item("SARDINES-155", "Sardines in Tomato Sauce 155g", "28.00", 60, 12),
            new Item("CORNED-BEEF-150", "Corned Beef 150g", "42.00", 40, 10),
            new Item("RICE-KG", "Well-milled Rice (per kilo)", "52.00", 150, 30),
            new Item("SUGAR-KG", "Brown Sugar (per kilo)", "85.00", 30, 8),
            new Item("COOKING-OIL-1L", "Cooking Oil 1L", "110.00", 25, 6),
            new Item("EGG-PC", "Egg (per piece)", "9.00", 180, 36),
            new Item("BAR-SOAP-60", "Bar Soap 60g", "25.00", 48, 12),
            new Item("SHAMPOO-SACHET", "Shampoo Sachet 12ml", "7.00", 3, 20),
            new Item("CANDLE-PC", "Candle (per piece)", "6.00", 50, 10),
            new Item("FEEDS-GROWER-50KG", "Hog Grower Feeds 50kg sack", "1650.00", 12, 4),
            new Item("FEEDS-STARTER-KG", "Hog Starter Feeds (per kilo)", "38.00", 90, 25),
            new Item("FEEDS-BOOSTER-KG", "Piglet Booster Feeds (per kilo)", "45.00", 5, 10));

    private final ShopRepository shops;
    private final AppUserRepository users;
    private final ProductRepository products;
    private final StockRepository stock;
    private final PasswordEncoder encoder;

    public DemoDataSeeder(ShopRepository shops, AppUserRepository users, ProductRepository products,
                          StockRepository stock, PasswordEncoder encoder) {
        this.shops = shops;
        this.users = users;
        this.products = products;
        this.stock = stock;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (shops.existsByDemoTrue()) {
            log.info("Demo data already present; leaving it untouched");
            return;
        }
        // Fail closed: if a demo email exists without a demo shop, something is wrong. Stop the app
        // rather than hand the demo logins to whoever holds that account.
        for (String email : List.of(OWNER_EMAIL, STAFF_EMAIL)) {
            if (users.existsByEmail(email)) {
                throw new IllegalStateException("Demo email " + email + " belongs to a non-demo account; refusing to seed");
            }
        }
        Shop shop = shops.save(Shop.demo(SHOP_NAME));
        AppUser owner = users.save(new AppUser(shop.getId(), OWNER_EMAIL, encoder.encode(OWNER_PASSWORD), Role.OWNER));
        users.save(new AppUser(shop.getId(), STAFF_EMAIL, encoder.encode(STAFF_PASSWORD), Role.STAFF));
        for (Item item : ITEMS) {
            Product product = products.saveAndFlush(new Product(shop.getId(), item.sku(), item.name(),
                    new BigDecimal(item.price()), item.threshold(), item.stock()));
            stock.recordOpeningStock(shop.getId(), product.getId(), item.stock(), owner.getId());
        }
        log.info("Seeded demo shop {} with {} products", shop.getId(), ITEMS.size());
    }
}
```

- [ ] **Step 5: Create `ops/reset-demo.sql`**

```sql
-- Manually resets the public demo shop. NEVER run by CI.
-- Usage (Azure): psql "<connection string for stockroom_app>" -f ops/reset-demo.sql, then restart the app to re-seed.
-- Deletes ONLY the shop flagged is_demo = true (only DemoDataSeeder can set that flag; no API can).
-- Names and emails are NOT trusted: a customer can pick any shop name. No flagged shop = error, nothing deleted.
DO $$
DECLARE
    demo_shop_id BIGINT;
BEGIN
    SELECT id INTO demo_shop_id FROM shop WHERE is_demo;

    IF demo_shop_id IS NULL THEN
        RAISE EXCEPTION 'Demo shop not found (no shop with is_demo = true). Nothing was deleted.';
    END IF;

    DELETE FROM stock_movement WHERE shop_id = demo_shop_id;
    DELETE FROM order_item WHERE shop_id = demo_shop_id;
    DELETE FROM customer_order WHERE shop_id = demo_shop_id;
    DELETE FROM product WHERE shop_id = demo_shop_id;
    DELETE FROM app_user WHERE shop_id = demo_shop_id;
    DELETE FROM shop WHERE id = demo_shop_id;

    RAISE NOTICE 'Demo shop % deleted. Restart the app to re-seed it.', demo_shop_id;
END $$;
```

- [ ] **Step 6: Run the tests, then the full suite**

Run: `./mvnw -B test -Dtest='OpenApiTest,DemoDataSeederTest'`. Expected: `Tests run: 6, Failures: 0`. (Demo tests: seeds once, ledger, reset scoped by flag, seeder fails closed.)

Run: `./mvnw -B test`. Expected: 0 failures.

- [ ] **Step 7: Try it by hand**

```bash
export JWT_SECRET="local-dev-secret-that-is-longer-than-32-bytes"
SPRING_PROFILES_ACTIVE=demo ./mvnw -B spring-boot:run
```

Open `http://localhost:8080/swagger-ui/index.html`. Run `POST /api/auth/login` with `{"email":"owner@demo.stockroom.dev","password":"demo-owner-2026"}`, click **Authorize** and paste the token, then run `GET /api/products?lowStock=true`. Expected: 2 products (the shampoo sachet and the piglet booster feeds). Stop the app with Ctrl+C.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Swagger UI, idempotent demo seeder, guarded demo reset script

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: CI on GitHub Actions and the public repository

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  push:
    branches: ["**"]
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: stockroom_test
          POSTGRES_USER: stockroom_test
          POSTGRES_PASSWORD: stockroom_test
        ports:
          - 5432:5432
        options: >-
          --health-cmd "pg_isready -U stockroom_test"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 10
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: Build and test
        run: ./mvnw -B verify
      - name: Upload test and coverage reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: reports
          path: |
            target/surefire-reports
            target/site/jacoco
      # The deploy workflow ships THIS jar, the one the tests just passed on. It is never rebuilt.
      - name: Upload application jar
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v7
        with:
          name: app-jar
          path: target/stockroom-api.jar
          retention-days: 14
```

- [ ] **Step 2: Run the same command locally**

Run: `./mvnw -B verify`

Expected: `BUILD SUCCESS`, and `target/site/jacoco/index.html` exists. Open it and check that `stock`, `order` and `security` have no untested branches that section 5–7 behavior depends on.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "ci: run the full test suite against Postgres on every push

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: CHECKPOINT: ask Wrenz before publishing**

Creating the repo publishes the code publicly. Ask: *"Ready to create the public repo github.com/WrenzLaylo/stockroom-api and push? (Every commit shows the Claude co-author line.)"* Continue only after an explicit yes.

- [ ] **Step 5: Create the repo, push, and watch CI**

```bash
gh repo create WrenzLaylo/stockroom-api --public --source . --remote origin --description "Multi-tenant inventory & orders API: Java 21, Spring Boot 4, PostgreSQL, Azure" --push
gh run watch --exit-status
```

Expected: the CI run finishes with ✓ `verify`. If it fails with `./mvnw: Permission denied` or `bad interpreter`, run `git update-index --chmod=+x mvnw`, check `.gitattributes` has `mvnw text eol=lf`, then commit and push again.

---

### Task 12: README and study guide

**Files:**
- Create: `README.md`, `STUDY_GUIDE.md`

- [ ] **Step 1: Create `README.md`**

````markdown
# Stockroom API

[![CI](https://github.com/WrenzLaylo/stockroom-api/actions/workflows/ci.yml/badge.svg)](https://github.com/WrenzLaylo/stockroom-api/actions/workflows/ci.yml)

A multi-tenant inventory and orders REST API for small shops, built with **Java 21, Spring Boot 4.1, Spring Security, JPA, Flyway and PostgreSQL**, and deployed to **Azure**.

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

The check and the deduction are atomic, PostgreSQL's row lock makes the second order wait and then re-check, and `CHECK (stock_qty >= 0)` is a second safety net. Order lines are processed in product ID order so two orders can't deadlock. `ConcurrencyTest` proves it: **20 threads buy 1 unit of a product with 5 in stock, and exactly 5 succeed.**

A related trap: JPA saving a whole `Product` row can write back stale values. `stock_qty` is mapped `updatable = false`, so only the guarded UPDATE ever changes it, and `@DynamicUpdate` makes an edit write only the columns it changed, so it can't un-archive a product (race tests cover both).

## Run it locally

Requirements: JDK 21 and PostgreSQL 16.

```bash
createdb stockroom_dev; createdb stockroom_test        # owned by user "stockroom", password "stockroom"
export JWT_SECRET="any-local-secret-of-at-least-32-bytes"
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run     # http://localhost:8080/swagger-ui/index.html
./mvnw verify                                          # all tests, against real Postgres, plus a JaCoCo report
```

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
- **Login rate limits live in memory:** fine for one instance, reset on restart. Multiple instances would need Redis.
- **Read isolation is enforced by the application** (every query filters by `shop_id`); PostgreSQL row-level security would be the next hardening step.
- **Azure tier:** see "Live demo" for the tier in use and what it means for response time.

## What I'd do next

An Angular front end, a "remove staff" flow with per-request user checks, row-level security, and splitting orders from inventory only if they ever need to scale separately (which would need an outbox and idempotent handlers).

---

Built by [Wrenz Laylo](https://github.com/WrenzLaylo) with Claude as a coding assistant. Every file was reviewed, is covered by tests, and I can explain and change any part of it.
````

- [ ] **Step 2: Create `STUDY_GUIDE.md`**

````markdown
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
9. *Why `updatable = false` on `stockQty`, and why `@DynamicUpdate`?* By default Hibernate updates all columns of a changed entity. Editing a product's name with an old copy would write the old stock back and erase a sale, or write `active = true` back over an archive. `updatable = false` means JPA never writes stock; `@DynamicUpdate` means an edit only writes the columns it changed.
10. *Why flush before and clear after the UPDATE?* Flush sends pending entity changes first; clear drops cached entities so nothing stale is used or re-saved afterwards.
11. *What happens if line 2 of an order fails?* An exception leaves the `@Transactional` method, so everything rolls back, including line 1's deduction and the order row.
12. *Why is the stock movement table append-only?* It's an audit trail: you can always explain the current stock. Test invariant: the sum of changes equals `stock_qty`.
13. *Why does cancelling return stock to archived products?* Cancelling reverses a sale; archiving only stops new sales. Refusing would falsify the history.
14. *How is double-cancel prevented?* `SELECT … FOR UPDATE` on the order row plus the status check: the second cancel waits, then sees CANCELLED and gets 409.

**Money**

15. *Why `BigDecimal`, not `double`?* Binary floating point can't represent 0.10 exactly; money needs exact decimal arithmetic. Rounding is HALF_UP at 2 decimals.
16. *Why store `unit_price` on each order line?* So a later price change doesn't rewrite past orders.

**Security**

17. *How does login work?* The email and password are checked against a BCrypt hash, and a JWT is signed with HS256 containing userId, shopId and role, valid for 1 hour.
18. *Why BCrypt, and why the 72-byte limit?* BCrypt is slow on purpose and salted. It only reads the first 72 bytes, so longer passwords are rejected rather than silently truncated; it counts bytes, not characters.
19. *Why the same error for a wrong password and an unknown email?* So login can't be used to discover which emails have accounts. Even the timing is similar, using a dummy hash.
20. *Where does shopId come from, and why?* Only from the validated token. If it came from the request, a user could just ask for another shop's data.
21. *Why 404 instead of 403 for another shop's product?* 403 would confirm the product exists.
22. *Why is CSRF disabled?* CSRF abuses cookies the browser sends automatically. This API uses bearer tokens in a header and has no cookies.
23. *How do security errors get the same JSON as other errors?* Security rejects requests in filters, before controllers, so `ProblemSecurityHandlers` forwards them to the MVC exception resolver, and `GlobalExceptionHandler` builds every body.
24. *What's the weakness of the JWT approach here?* Claims are trusted until expiry, so a removed user keeps access for up to an hour. That's documented; a "remove staff" feature must add a per-request check.
25. *How does the rate limiter handle faked IPs?* Azure appends the real client IP to the end of `X-Forwarded-For`; only that last entry is trusted.

**Multi-tenancy and the database**

26. *What do the composite foreign keys add?* The database itself rejects an order line or movement that points at another shop's product, even if the Java code has a bug.
27. *What would row-level security add?* Database-enforced reads too. It's the next hardening step.
28. *Why Flyway with `ddl-auto: validate`?* The schema is versioned SQL you can review; Hibernate only checks that the entities match it and never changes the database.

**Testing and operations**

29. *Why test against real PostgreSQL instead of H2?* Locking, constraint names and SQL behavior differ, and the concurrency tests only mean something on the real database.
30. *How do you know the concurrency test isn't passing by luck?* Remove the `stock_qty >= ?` guard and it fails. I tried it.
31. *What does CI do?* Every push starts Postgres in a container and runs `./mvnw verify` (all tests plus coverage). Pushes to main deploy to Azure only after CI passes.
32. *How did you use AI on this project?* Claude wrote the first version from a design I agreed and an external review. I reviewed every file, the tests prove the behavior, and I can change any part myself (see the practice change).

## Practice change (do this alone)

**Add a rule: a single order line may not exceed 10 units for products whose SKU starts with `FEEDS-`** (limited stock per customer).

Hints, only if stuck:
- Where do per-line rules live? (`OrderRules`, or the service once the product is known)
- The error should be `400 VALIDATION_FAILED` with field `items`.
- Write the test first in `OrderIntegrationTest`: 11 units of a `FEEDS-` product → 400 and stock unchanged; 11 units of another product → 201.

Done when `./mvnw verify` passes and you can explain why your rule sits where it does.
````

- [ ] **Step 3: Commit and push**

```bash
git add README.md STUDY_GUIDE.md
git commit -m "docs: README with design notes, and a study guide

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 13: Azure deployment and CD

**Files:**
- Create: `src/main/resources/application-prod.yml`, `.github/workflows/deploy.yml`
- Modify: `README.md` (add a "Live demo" section)

- [ ] **Step 1: Add the production profile `src/main/resources/application-prod.yml`**

```yaml
# Production: no defaults, so a missing setting fails at startup instead of connecting somewhere wrong.
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}

stockroom:
  rate-limit:
    # Only here: on App Service every request arrives through Azure's front end, which appends the client IP.
    trust-forwarded-for: true
```

Commit it: `git add -A && git commit -m "feat: production profile with required settings" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`.

- [ ] **Step 2: Install the Azure CLI and sign in**

Run: `winget install -e --id Microsoft.AzureCLI --accept-source-agreements --accept-package-agreements`

Then Wrenz runs `! az login` in the session (an interactive browser login).

Run: `az account show --query "{name:name, id:id}" -o table`. Expected: his subscription.

- [ ] **Step 3: CHECKPOINT: the cost gate (spec section 8)**

Look up current prices for Southeast Asia:

```bash
curl -s "https://prices.azure.com/api/retail/prices?\$filter=serviceName%20eq%20'Azure%20App%20Service'%20and%20armRegionName%20eq%20'southeastasia'%20and%20skuName%20eq%20'B1'%20and%20priceType%20eq%20'Consumption'" | py -3.12 -c "import json,sys; [print(i['productName'], i['meterName'], i['retailPrice'], i['unitOfMeasure']) for i in json.load(sys.stdin)['Items'] if 'Linux' in i['productName']]"
curl -s "https://prices.azure.com/api/retail/prices?\$filter=serviceName%20eq%20'Azure%20Database%20for%20PostgreSQL'%20and%20armRegionName%20eq%20'southeastasia'%20and%20skuName%20eq%20'B1ms'%20and%20priceType%20eq%20'Consumption'" | py -3.12 -c "import json,sys; [print(i['productName'], i['meterName'], i['retailPrice'], i['unitOfMeasure']) for i in json.load(sys.stdin)['Items']]"
```

Show Wrenz:
- **App Service F1:** $0/month.
- **B1 Linux:** the hourly price × 730 hours.
- **PostgreSQL B1ms:** the hourly price × 730 hours, plus 32 GB of storage, and note that it's covered for 12 months **only** if his subscription is an Azure free account.

Ask him to confirm which account type he has. **Stop** until Wrenz says yes to creating: the resource group `rg-stockroom`, the PostgreSQL Flexible Server B1ms, and an F1 App Service plan and web app.

- [ ] **Step 4: Create the database server**

```bash
SUFFIX=$(openssl rand -hex 3)
RG=rg-stockroom; LOC=southeastasia
DB_SERVER=stockroom-db-$SUFFIX
APP=stockroom-api-$SUFFIX
ADMIN_PW=$(openssl rand -base64 30 | tr -d '/+=')
APP_DB_PW=$(openssl rand -base64 30 | tr -d '/+=')
printf 'RG=%s\nDB_SERVER=%s\nAPP=%s\nADMIN_PW=%s\nAPP_DB_PW=%s\n' "$RG" "$DB_SERVER" "$APP" "$ADMIN_PW" "$APP_DB_PW" > .env   # gitignored; keep a copy in a password manager

az group create --name $RG --location $LOC
az postgres flexible-server create --resource-group $RG --name $DB_SERVER --location $LOC \
  --tier Burstable --sku-name Standard_B1ms --storage-size 32 --version 16 \
  --admin-user stockroomadmin --admin-password "$ADMIN_PW" --public-access None --yes
az postgres flexible-server db create --resource-group $RG --server-name $DB_SERVER --database-name stockroom
```

Expected: each command returns JSON with `"provisioningState": "Succeeded"` (the server takes about 5–10 minutes).

- [ ] **Step 5: Create a least-privilege app user (temporarily allowing this PC's IP)**

```bash
MY_IP=$(curl -s https://api.ipify.org)
az postgres flexible-server firewall-rule create --resource-group $RG --name $DB_SERVER --rule-name setup-temp --start-ip-address $MY_IP --end-ip-address $MY_IP
PGPASSWORD="$ADMIN_PW" "/c/Program Files/PostgreSQL/16/bin/psql" "host=$DB_SERVER.postgres.database.azure.com port=5432 dbname=stockroom user=stockroomadmin sslmode=require" <<SQL
CREATE ROLE stockroom_app LOGIN PASSWORD '$APP_DB_PW';
GRANT CONNECT ON DATABASE stockroom TO stockroom_app;
CREATE SCHEMA stockroom AUTHORIZATION stockroom_app;
ALTER ROLE stockroom_app SET search_path = stockroom;
SQL
az postgres flexible-server firewall-rule delete --resource-group $RG --name $DB_SERVER --rule-name setup-temp --yes
```

Expected: `CREATE ROLE`, `GRANT`, `CREATE SCHEMA`, `ALTER ROLE`. (PostgreSQL 16 already denies everyone CREATE on the `public` schema, so the app user can only create objects in its own schema.) The temporary rule is then deleted.

- [ ] **Step 6: Create the web app on F1 and configure it**

```bash
JWT_SECRET=$(openssl rand -base64 48)
echo "JWT_SECRET=$JWT_SECRET" >> .env
az appservice plan create --resource-group $RG --name plan-stockroom --location $LOC --sku F1 --is-linux
az webapp create --resource-group $RG --plan plan-stockroom --name $APP --runtime "JAVA:21-java21"
az webapp config appsettings set --resource-group $RG --name $APP --settings \
  SPRING_PROFILES_ACTIVE=prod,demo \
  SERVER_PORT=80 \
  DB_URL="jdbc:postgresql://$DB_SERVER.postgres.database.azure.com:5432/stockroom?sslmode=require" \
  DB_USER=stockroom_app DB_PASSWORD="$APP_DB_PW" JWT_SECRET="$JWT_SECRET"
az webapp update --resource-group $RG --name $APP --https-only true
```

If `az webapp create` rejects the runtime string, run `az webapp list-runtimes --os linux | grep -i java` and use the Java 21 SE entry it lists.

- [ ] **Step 7: Allow only the app's outbound IPs through the database firewall**

```bash
i=0
for ip in $(az webapp show --resource-group $RG --name $APP --query possibleOutboundIpAddresses -o tsv | tr ',' ' '); do
  i=$((i+1))
  az postgres flexible-server firewall-rule create --resource-group $RG --name $DB_SERVER --rule-name app-out-$i --start-ip-address $ip --end-ip-address $ip -o none
done
az postgres flexible-server firewall-rule list --resource-group $RG --name $DB_SERVER -o table
```

Expected: one `app-out-N` rule per address, and **no** `AllowAllAzureServices` rule (spec section 8).

- [ ] **Step 8: Deploy once by hand, then smoke-test**

```bash
./mvnw -B -DskipTests package
az webapp deploy --resource-group $RG --name $APP --src-path target/stockroom-api.jar --type jar
healthy=false
for i in $(seq 1 30); do
  if curl -fsS https://$APP.azurewebsites.net/actuator/health; then healthy=true; break; fi
  sleep 10
done
[ "$healthy" = true ] || { echo "Health check never passed after 5 minutes"; exit 1; }
TOKEN=$(curl -fsS -X POST https://$APP.azurewebsites.net/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@demo.stockroom.dev","password":"demo-owner-2026"}' | py -3.12 -c "import json,sys; print(json.load(sys.stdin)['accessToken'])")
curl -fsS "https://$APP.azurewebsites.net/api/products?lowStock=true" -H "Authorization: Bearer $TOKEN"
```

Expected: `{"status":"UP"}`, then a page containing 2 low-stock products. If health never comes up, run `az webapp log tail --resource-group $RG --name $APP` and look for the port it bound to, or a database connection error.

- [ ] **Step 9: Measure F1, then let Wrenz choose the tier**

```bash
az webapp log tail --resource-group $RG --name $APP 2>&1 | grep -m1 "Started StockroomApiApplication"   # restart first: az webapp restart -g $RG -n $APP
az monitor metrics list --resource $(az webapp show -g $RG -n $APP --query id -o tsv) --metric MemoryWorkingSet --interval PT5M -o table
```

Report the startup time, peak memory, and the time for the first request after a 20-minute idle. Show them next to the B1 price from Step 3. Wrenz chooses:
- Stay on F1
- Or upgrade: `az appservice plan update --resource-group $RG --name plan-stockroom --sku B1`

If B1 is chosen, also run `az webapp config set -g $RG -n $APP --always-on true --generic-configurations '{"healthCheckPath":"/actuator/health"}'`.

- [ ] **Step 10: Continuous deployment**

Create `.github/workflows/deploy.yml`:

```yaml
name: Deploy

on:
  workflow_run:
    workflows: ["CI"]
    types: [completed]
    branches: [main]

# One deploy at a time; a queued run never cancels one that is mid-deploy.
concurrency:
  group: production-deploy
  cancel-in-progress: false

permissions:
  contents: read
  actions: read

jobs:
  deploy:
    if: github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push'
    runs-on: ubuntu-latest
    steps:
      # If an older CI run finishes after a newer one, don't let it replace the newer release.
      - name: Skip if main has moved on
        id: fresh
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          latest=$(gh api "repos/${{ github.repository }}/commits/main" --jq .sha)
          if [ "$latest" = "${{ github.event.workflow_run.head_sha }}" ]; then
            echo "deploy=true" >> "$GITHUB_OUTPUT"
          else
            echo "deploy=false" >> "$GITHUB_OUTPUT"
            echo "main is now at $latest; skipping the older ${{ github.event.workflow_run.head_sha }}"
          fi
      - name: Download the jar CI tested
        if: steps.fresh.outputs.deploy == 'true'
        uses: actions/download-artifact@v7
        with:
          name: app-jar
          run-id: ${{ github.event.workflow_run.id }}
          github-token: ${{ github.token }}
          path: dist
      - name: Deploy to Azure App Service
        if: steps.fresh.outputs.deploy == 'true'
        uses: azure/webapps-deploy@v3
        with:
          app-name: ${{ vars.AZURE_WEBAPP_NAME }}
          publish-profile: ${{ secrets.AZURE_WEBAPP_PUBLISH_PROFILE }}
          package: dist/stockroom-api.jar
      - name: Smoke test
        if: steps.fresh.outputs.deploy == 'true'
        run: |
          for i in $(seq 1 30); do
            curl -fsS "https://${{ vars.AZURE_WEBAPP_NAME }}.azurewebsites.net/actuator/health" && exit 0
            sleep 10
          done
          exit 1
```

Enable publish-profile auth for the app and store the profile as a GitHub secret (it's never written to the repo):

```bash
az resource update --resource-group $RG --name scm --namespace Microsoft.Web --resource-type basicPublishingCredentialsPolicies --parent sites/$APP --set properties.allow=true
az webapp deployment list-publishing-profiles --resource-group $RG --name $APP --xml | gh secret set AZURE_WEBAPP_PUBLISH_PROFILE
gh variable set AZURE_WEBAPP_NAME --body "$APP"
```

- [ ] **Step 11: Add the live link to the README, then push**

In `README.md`, replace the line `- **Live Swagger UI:** see "Live demo" below (added once deployed)` with `- **Live Swagger UI:** https://<APP>.azurewebsites.net/swagger-ui/index.html` (the real app name). Add this section before "Known limits":

```markdown
## Live demo

- Swagger UI: https://<APP>.azurewebsites.net/swagger-ui/index.html
- Hosting: Azure App Service (<F1 or B1>, Southeast Asia) + Azure Database for PostgreSQL Flexible Server (B1ms)
- <If F1:> The free tier sleeps when idle, so the first request after a quiet period can take ~<measured> seconds.
- Deploys automatically from `main` after CI passes, then runs a health-check smoke test.
```

Fill each `<…>` with the real values measured in Step 9. Then:

```bash
git add -A
git commit -m "ci: deploy to Azure App Service after CI passes; document the live demo

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
gh run watch --exit-status
```

Expected: CI ✓, then Deploy ✓ with the smoke test passing. The Swagger link opens.

- [ ] **Step 12: Update the Rocket Partners resume**

In `C:\Users\OASIS\Downloads\Wrenz_Laylo_Rocket_Cloud_Developer_Resume.txt`, replace the `[SPRING BOOT PROJECT NAME]` block with the real project, using real numbers only. Get the test count from `grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[ ,]+' '{s+=$3} END {print s}'`.

```
Stockroom API | Personal project | 2026 | github.com/WrenzLaylo/stockroom-api
- Multi-tenant inventory and orders REST API in Java 21 / Spring Boot 4 with Spring Security (JWT, owner and staff roles), JPA, Flyway and PostgreSQL, deployed to Azure App Service with GitHub Actions CI/CD.
- Orders deduct stock with one guarded SQL update in product ID order, so concurrent orders can't oversell or deadlock; a 20-thread test proves exactly 5 of 20 buyers get the last 5 units.
- <N> automated tests against real PostgreSQL, covering every endpoint, role, error code, and cross-shop read and write attempt.
```

Then build it: `py -3.12 C:/Users/OASIS/Downloads/_build_onepage_resume_docx.py C:/Users/OASIS/Downloads/Wrenz_Laylo_Rocket_Cloud_Developer_Resume.txt && py -3.11 C:/Users/OASIS/Downloads/_docx2pdf.py C:/Users/OASIS/Downloads/Wrenz_Laylo_Rocket_Cloud_Developer_Resume.docx`. Expected: `pages: 1`. If it spills to 2 pages, cut the WordPress bullet first.
