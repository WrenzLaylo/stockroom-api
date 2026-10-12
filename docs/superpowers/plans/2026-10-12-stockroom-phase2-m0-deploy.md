# Stockroom Phase 2, Milestone M0: Free Azure Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy today's API to Azure App Service F1 with a Neon Free PostgreSQL database at $0 a month, deploy every merge to `main` automatically, and measure the free tier so the launcher-page decision is made on real numbers.

**Architecture:** One Spring Boot jar on App Service F1 (Linux, Java 21, Southeast Asia) talks to Neon over a direct TLS connection as a least-privilege role. A `deploy` job in the existing CI workflow ships the exact jar the tests passed on, signing in to Azure with GitHub OIDC. Production settings live in a new `prod` profile; forwarded-header handling is switched off explicitly so the rate limiter's "last `X-Forwarded-For` entry" rule holds on Azure.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16 (Neon), Azure CLI, GitHub Actions (`azure/login`, `azure/webapps-deploy`), JDK `HttpClient` for the real-server test.

**Spec:** `docs/superpowers/specs/2026-10-12-stockroom-phase2-design.md` (sections 3, 9.2, 9.3, 12). Read section 3 before starting.

**Not in M0 (by design):** the long-lived cache headers for Angular's hashed files (spec 3.3) arrive with the Angular build in M2; the launcher page (spec 5.8), if Task 3 triggers it, is built in M2 because it redirects to the Angular app.

## Global Constraints

- **$0 forever:** create only App Service **F1** and Neon **Free**. No Application Insights, no Log Analytics, no Azure Container Registry, no Azure PostgreSQL, no other SKU.
- **Nothing is created in Azure before Wrenz has seen the exact commands** (Task 2 Step 6 is the gate). Wrenz runs `az login` himself.
- Region: Azure `southeastasia`; Neon `aws-ap-southeast-1` (Singapore), PostgreSQL **16** (matches CI and local).
- Neon: the **direct** connection host (never a `-pooler` host).
- Secrets (`.env`, passwords, the JWT secret, connection strings) are never committed, never printed in a tool result, never pasted into a PR. `.env` is already gitignored.
- Resume and README claims: "deployed on Azure App Service with Neon PostgreSQL". Never "Azure Database for PostgreSQL".
- Git: work on branch `m0/deploy`, open a PR to `main`, Wrenz merges. **No `Co-Authored-By` or "Generated with Claude Code" lines** in commits or PRs.
- Never kill processes by name (`Stop-Process -Name`, `taskkill /IM`). Kill only PIDs you started.
- Bash heredocs that contain apostrophes break in this harness: write files with the Write tool.
- Java builds: run `. .superpowers/env.sh` first (sets `JAVA_HOME` to Temurin 21; Java 18 is also installed, never use it). Local PostgreSQL 16 must be running (test DB `stockroom_test`, user/password `stockroom_test`).
- Git Bash rewrites arguments that start with `/` into Windows paths. Prefix every `az` command whose arguments contain an Azure resource ID (`/subscriptions/...`) with `MSYS_NO_PATHCONV=1`.

## Review Focus

1. **A visitor after hours of idle:** the first request after F1 unloaded must still succeed, well inside App Service's 230-second front-end timeout. Pinned by the idle measurements in Task 3 Step 6 (fail the milestone if any sample exceeds 230 s or returns non-200).
2. **Neon asleep while F1 is awake:** the first query after Neon's 5-minute suspend must wait and succeed, not fail. Pinned by Task 3 Step 8.
3. **A forged `X-Forwarded-For` on the live app:** the login IP limit must key on the real client. Pinned by `ProductionHttpTest` (Task 1) and the live probe (Task 3 Step 5).
4. **Two merges to `main` in quick succession:** an older run must never redeploy over a newer one. Pinned by the "Skip if main has moved on" step (Task 4) and its check in Task 5 Step 6.
5. **Secrets leaking:** `.env` must stay untracked and app settings must never be echoed. Pinned by `git check-ignore` (Task 2 Step 4) and by listing setting **names** only (Task 2 Step 7).

---

### Task 1: Production profile, forwarded headers off, compression, relative Swagger URL

**Files:**
- Modify: `src/main/resources/application.yml` (the `server:` block)
- Create: `src/main/resources/application-prod.yml`
- Modify: `src/main/java/dev/wrenz/stockroom/config/OpenApiConfig.java`
- Modify: `src/test/java/dev/wrenz/stockroom/config/OpenApiTest.java`
- Create: `src/test/java/dev/wrenz/stockroom/config/ProductionHttpTest.java`

**Interfaces:**
- Consumes: `LoginRateLimiter` limits from `application.yml` (`max-attempts-per-ip: 30`), `TestDatabaseGuard` (test support).
- Produces: profile `prod` (used by Task 2's `SPRING_PROFILES_ACTIVE=prod,demo`), env vars `DB_URL`, `DB_USER`, `DB_PASSWORD` required in `prod`.

- [ ] **Step 1: Create the branch**

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
git checkout main && git pull
git checkout -b m0/deploy
```

- [ ] **Step 2: Write the failing Swagger test**

Add this test to `src/test/java/dev/wrenz/stockroom/config/OpenApiTest.java`, after `swaggerSendsNoTokenToLoginOrRegister`:

```java
    @Test
    void swaggerCallsTheServerItWasLoadedFrom() throws Exception {
        // Behind Azure's TLS termination the app sees http://, so a computed server URL would send
        // "Try it out" calls to http://. A relative URL keeps them on whatever scheme the page used.
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.servers[0].url").value("/"));
    }
```

- [ ] **Step 3: Write the failing real-server test**

Create `src/test/java/dev/wrenz/stockroom/config/ProductionHttpTest.java`:

```java
package dev.wrenz.stockroom.config;

import dev.wrenz.stockroom.support.TestDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real embedded Tomcat as if on Azure App Service. MockMvc skips Tomcat, so it can't see
 * what Tomcat's valves and compression do to a request (spec section 3.3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.cloud-platform=azure_app_service",
        "stockroom.rate-limit.trust-forwarded-for=true"})
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class ProductionHttpTest {

    /** The address Azure's front end appends as the last X-Forwarded-For entry. */
    private static final String REAL_CLIENT = "198.51.100.7";

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aForgedForwardedForEntryCannotChangeTheRateLimitedAddress() throws Exception {
        for (int attempt = 1; attempt <= 30; attempt++) {
            assertThat(login(attempt).statusCode()).isEqualTo(401);
        }
        // Every request claimed a different first address, but the real client is the same one: limited.
        assertThat(login(31).statusCode()).isEqualTo(429);
    }

    @Test
    void largeResponsesAreCompressed() throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(url("/v3/api-docs")).header("Accept-Encoding", "gzip").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
    }

    private HttpResponse<String> login(int attempt) throws Exception {
        String body = "{\"email\":\"spray" + attempt + "@shop.test\",\"password\":\"wrong-password\"}";
        return http.send(HttpRequest.newBuilder(url("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", "203.0.113." + attempt + ", " + REAL_CLIENT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
```

- [ ] **Step 4: Run both tests and watch them fail**

```bash
. .superpowers/env.sh
./mvnw -B test -Dtest='OpenApiTest,ProductionHttpTest'
```

Expected: 3 failures.
- `swaggerCallsTheServerItWasLoadedFrom`: the computed URL is something like `http://localhost`, not `/`.
- `aForgedForwardedForEntryCannotChangeTheRateLimitedAddress`: attempt 31 returns **401**, not 429. With the Azure platform active, Spring Boot defaults `server.forward-headers-strategy` to `native`; Tomcat's `RemoteIpValve` rewrites `X-Forwarded-For` to the forged first entry, so every attempt counts against a different address.
- `largeResponsesAreCompressed`: no `Content-Encoding` header.

If the forwarded-header test **passes** at this step, stop and report it: it would mean the spec's review finding B1 does not reproduce, which changes the reasoning (keep the `none` setting anyway, and record the observation in the ledger).

- [ ] **Step 5: Turn off forwarded headers and turn on compression**

In `src/main/resources/application.yml`, replace the `server:` block with:

```yaml
server:
  port: 8080
  # Always "none", in every profile. On Azure App Service, Spring Boot would otherwise default this to "native",
  # and Tomcat's RemoteIpValve would rewrite X-Forwarded-For so that a client-supplied (forged) entry ends up last.
  # ClientIpResolver trusts the last entry (the one Azure appends), so "native" would make the login IP limit
  # bypassable on Azure only. ProductionHttpTest proves it.
  forward-headers-strategy: none
  # F1 allows 165 MB of outbound traffic a day. Responses under 2 KB (such as token responses) are never compressed.
  compression:
    enabled: true
    mime-types: application/json,application/problem+json,text/html,text/css,text/javascript,application/javascript,image/svg+xml
  error:
    include-stacktrace: never
    include-message: never
```

- [ ] **Step 6: Give Swagger a relative server URL**

Replace `src/main/java/dev/wrenz/stockroom/config/OpenApiConfig.java` with:

```java
package dev.wrenz.stockroom.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** Swagger UI is an interactive demo: log in with a demo account, then "Authorize" with the token. */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Stockroom API", version = "1.0",
                description = "Multi-tenant inventory and orders for small shops. Stock can't go negative, even with concurrent orders."),
        // Relative on purpose: behind Azure's TLS termination the app sees http://, and forwarded headers are off
        // (see application.yml), so a computed URL would point "Try it out" at http://.
        servers = @Server(url = "/", description = "This server"),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
```

- [ ] **Step 7: Run the tests and watch them pass**

```bash
./mvnw -B test -Dtest='OpenApiTest,ProductionHttpTest'
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 8: Prove the forwarded-header test can fail**

Comment out the `forward-headers-strategy: none` line in `application.yml`, run `./mvnw -B test -Dtest=ProductionHttpTest#aForgedForwardedForEntryCannotChangeTheRateLimitedAddress`, and confirm it fails with "expected 429 but was 401". Restore the line and run it again: PASS. Note both results for the ledger (Task 3 Step 9 creates it).

- [ ] **Step 9: Add the production profile**

Create `src/main/resources/application-prod.yml`:

```yaml
# Production (Azure App Service F1 + Neon Free). No defaults for the database, so a missing setting fails at
# startup instead of connecting somewhere wrong. Values come from App Service settings (spec section 3.3).
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 5
      # Neon Free scales to zero after 5 idle minutes and has 100 compute-hours a month: keep no idle connections.
      minimum-idle: 0
      idle-timeout: 60000
      # A request that wakes Neon waits for it instead of failing.
      connection-timeout: 10000

stockroom:
  rate-limit:
    # Only here: on App Service every request arrives through Azure's front end, which appends the client IP.
    trust-forwarded-for: true
```

- [ ] **Step 10: Run the whole suite**

```bash
./mvnw -B verify
```

Expected: `Tests run: 116, Failures: 0, Errors: 0` (113 before, plus 3), `BUILD SUCCESS`.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/application.yml src/main/resources/application-prod.yml \
  src/main/java/dev/wrenz/stockroom/config/OpenApiConfig.java \
  src/test/java/dev/wrenz/stockroom/config/OpenApiTest.java \
  src/test/java/dev/wrenz/stockroom/config/ProductionHttpTest.java
git commit -m "feat: prod profile; forwarded headers off so a forged X-Forwarded-For can't dodge the rate limit on Azure; compression; relative Swagger URL"
```

---

### Task 2: Provision Neon and App Service F1 (checkpoint before anything is created)

**Files:**
- Create: `ops/create-app-role.sql`
- Local only (gitignored): `.env`

**Interfaces:**
- Consumes: the `prod` profile and its env vars (Task 1).
- Produces for Tasks 3 to 5, saved in `.env`: `NEON_OWNER_URL`, `APP_DB_PW`, `RG=rg-stockroom`, `APP` (the web app name), `JWT_SECRET`. Azure resources: resource group `rg-stockroom`, plan `plan-stockroom` (F1 Linux), web app `$APP`.

- [ ] **Step 1: CHECKPOINT: accounts**

Ask Wrenz:
1. Does he already have an Azure subscription? If not, he creates a free account at https://azure.microsoft.com/free (a card is required for identity checks). Tell him plainly: **after 30 days the free account must be upgraded to pay-as-you-go** or Azure disables it; with only F1 and nothing else created, pay-as-you-go stays at $0. Record the account's creation date in the ledger with the day-30 upgrade date.
2. He signs up at https://neon.com (GitHub sign-in is fine; no card).

Wait for both.

- [ ] **Step 2: Install the Azure CLI and sign in**

Run in PowerShell:

```powershell
winget install -e --id Microsoft.AzureCLI --accept-source-agreements --accept-package-agreements
```

Then Wrenz runs `! az login` in the session (a browser login). Verify (Git Bash):

```bash
az account show --query "{name:name, id:id, state:state}" -o table
az provider show --namespace Microsoft.Web --query registrationState -o tsv
```

Expected: his subscription, `state` = `Enabled`; provider `Registered`. If the provider says `NotRegistered`, run `az provider register --namespace Microsoft.Web --wait`. If `az` is not found in Git Bash, open a new shell (the installer updates `PATH`).

- [ ] **Step 3: Wrenz creates the Neon project (browser)**

Give him these steps:
1. **New project:** name `stockroom`, PostgreSQL **16**, cloud **AWS**, region **Asia Pacific (Singapore)**.
2. **Branches → main → Computes → Edit:** set the autoscaling **maximum to 0.25 CU** (minimum stays 0.25). Leave scale to zero on.
3. **Databases → New database:** name `stockroom`, owner `neondb_owner`.
4. **Connect** (dashboard): database `stockroom`, role `neondb_owner`, **Connection pooling OFF**. Copy the connection string; its host must **not** contain `-pooler`.
5. Open `C:\Users\OASIS\Documents\GitHub\stockroom-api\.env` in Notepad (create it if missing) and add one line: `NEON_OWNER_URL=<the connection string>`. Save.

Do not ask him to paste the string into the chat.

- [ ] **Step 4: Check that `.env` can never be committed**

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
git check-ignore -q .env && echo "ignored: ok"
grep -c '^NEON_OWNER_URL=postgres' .env
```

Expected: `ignored: ok` and `1`. Never `cat` the file.

- [ ] **Step 5: Create the least-privilege app role**

Create `ops/create-app-role.sql` with the Write tool:

```sql
-- One-time setup of the least-privilege application role (Neon, or any PostgreSQL 16).
-- Run as the database owner:
--   psql "<owner connection string>" -v ON_ERROR_STOP=1 -v app_pw=<password> -f ops/create-app-role.sql
-- The app role can connect and owns only its own schema. PostgreSQL 15+ already denies CREATE on "public".
CREATE ROLE stockroom_app LOGIN PASSWORD :'app_pw';
GRANT CONNECT ON DATABASE stockroom TO stockroom_app;
CREATE SCHEMA stockroom AUTHORIZATION stockroom_app;
ALTER ROLE stockroom_app SET search_path = stockroom;
```

Then run it:

```bash
set -a; . ./.env; set +a
APP_DB_PW=$(openssl rand -base64 30 | tr -d '/+=')
printf 'APP_DB_PW=%s\n' "$APP_DB_PW" >> .env
"/c/Program Files/PostgreSQL/16/bin/psql" "$NEON_OWNER_URL" -v ON_ERROR_STOP=1 -v app_pw="$APP_DB_PW" -f ops/create-app-role.sql
```

Expected output: `CREATE ROLE`, `GRANT`, `CREATE SCHEMA`, `ALTER ROLE`.

Commit the script (no secrets in it):

```bash
git add ops/create-app-role.sql
git commit -m "ops: script for the least-privilege application role"
```

- [ ] **Step 6: CHECKPOINT: show Wrenz what will be created and what it costs**

Confirm F1 is metered at $0:

```bash
curl -s "https://prices.azure.com/api/retail/prices?\$filter=serviceName%20eq%20'Azure%20App%20Service'%20and%20armRegionName%20eq%20'southeastasia'%20and%20skuName%20eq%20'F1'" | py -3.12 -c "import json,sys; [print(i['productName'], '|', i['meterName'], '|', i['retailPrice'], i['unitOfMeasure']) for i in json.load(sys.stdin)['Items']]"
```

Expected: the Linux F1 line shows `0.0`.

Show Wrenz this list and the exact commands of Step 7, then **stop until he says yes**:
- Resource group `rg-stockroom` in `southeastasia` (free)
- App Service plan `plan-stockroom`, **F1**, Linux ($0.00/hour)
- Web app `stockroom-wrenz` (or `stockroom-wrenz-<4 hex>` if the name is taken), Java 21, HTTPS only
- App settings: `SPRING_PROFILES_ACTIVE`, `SERVER_PORT`, `JAVA_OPTS`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`
- Nothing else: no Application Insights, no Log Analytics.

- [ ] **Step 7: Create the plan, the web app and its settings**

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
set -a; . ./.env; set +a
RG=rg-stockroom; LOC=southeastasia; APP=stockroom-wrenz
az group create --name $RG --location $LOC -o none
az appservice plan create --resource-group $RG --name plan-stockroom --location $LOC --sku F1 --is-linux -o none
az webapp create --resource-group $RG --plan plan-stockroom --name $APP --runtime "JAVA:21-java21" -o none \
  || { APP=stockroom-wrenz-$(openssl rand -hex 2); az webapp create --resource-group $RG --plan plan-stockroom --name $APP --runtime "JAVA:21-java21" -o none; }
JWT_SECRET=$(openssl rand -base64 48)
printf 'RG=%s\nAPP=%s\nJWT_SECRET=%s\n' "$RG" "$APP" "$JWT_SECRET" >> .env
echo "web app: $APP"
```

If both `az webapp create` calls fail on the runtime string, run `az webapp list-runtimes --os linux -o tsv | grep -i java` and use the Java SE 21 entry it lists.

Build the settings as a JSON file (passing a URL that contains `&` on the `az` command line breaks on Windows), apply it, and delete it:

```bash
NEON_HOST=$(printf '%s' "$NEON_OWNER_URL" | sed -E 's#^postgres(ql)?://[^@]+@([^/:?]+).*#\2#')
case "$NEON_HOST" in *-pooler*) echo "STOP: this is the pooled host; copy the direct connection string"; exit 1;; esac
SETTINGS="C:/Users/OASIS/AppData/Local/Temp/stockroom-appsettings.json"
export NEON_HOST APP_DB_PW JWT_SECRET SETTINGS
py -3.12 -c "import json,os; s={'SPRING_PROFILES_ACTIVE':'prod,demo','SERVER_PORT':'80','JAVA_OPTS':'-XX:MaxRAMPercentage=70 -XX:TieredStopAtLevel=1','DB_URL':'jdbc:postgresql://'+os.environ['NEON_HOST']+'/stockroom?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory','DB_USER':'stockroom_app','DB_PASSWORD':os.environ['APP_DB_PW'],'JWT_SECRET':os.environ['JWT_SECRET']}; json.dump([{'name':k,'value':v,'slotSetting':False} for k,v in s.items()], open(os.environ['SETTINGS'],'w'))"
az webapp config appsettings set --resource-group $RG --name $APP --settings "@$SETTINGS" -o none
rm -f "$SETTINGS"
az webapp update --resource-group $RG --name $APP --https-only true -o none
az webapp config appsettings list --resource-group $RG --name $APP --query "[].name" -o tsv
az resource list --resource-group $RG --query "[].{name:name, type:type}" -o table
```

Expected: the seven setting **names** (never print values); exactly two resources, the `serverfarms` plan and the `sites` web app.

- [ ] **Step 8: Wrenz adds the $1 budget (portal)**

Give him these steps: https://portal.azure.com → **Cost Management + Billing** → **Cost Management** → **Budgets** → **+ Add**. Scope: his subscription. Name `stockroom-1usd`, reset period **Monthly**, amount **1** (USD). Alerts: **Actual 50%** and **Actual 100%**, to his email. Create. Ask him to confirm it shows in the Budgets list.

---

### Task 3: First deploy, smoke test, measurements and the launcher decision

**Files:**
- Create (gitignored ledger): `.superpowers/sdd/2026-10-12-phase2/progress.md`

**Interfaces:**
- Consumes: `.env` values and Azure resources from Task 2; the jar from Task 1.
- Produces: measured cold start, memory, CPU-quota use, Neon wake, forged-header result; the launcher decision (used by the M2 plan).

- [ ] **Step 1: Build and deploy the jar by hand**

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
. .superpowers/env.sh
set -a; . ./.env; set +a
./mvnw -B -DskipTests package
az webapp deploy --resource-group $RG --name $APP --src-path target/stockroom-api.jar --type jar
```

Expected: the deploy command ends without an error (it can take a few minutes).

- [ ] **Step 2: Smoke test**

```bash
BASE=https://$APP.azurewebsites.net
healthy=false
for i in $(seq 1 30); do
  if curl -fsS --max-time 60 $BASE/actuator/health | grep -q '"status":"UP"'; then healthy=true; break; fi
  sleep 10
done
[ "$healthy" = true ] || { echo "Health never reported UP"; exit 1; }
TOKEN=$(curl -fsS -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@demo.stockroom.dev","password":"demo-owner-2026"}' | py -3.12 -c "import json,sys; print(json.load(sys.stdin)['accessToken'])")
curl -fsS "$BASE/api/products?lowStock=true" -H "Authorization: Bearer $TOKEN" | py -3.12 -c "import json,sys; d=json.load(sys.stdin); print('low-stock products:', len(d['content']))"
```

Expected: health `UP`, then `low-stock products:` followed by a number of at least 1.

If health never comes up, run `az webapp log tail --resource-group $RG --name $APP` and look for:
- **The port:** the log must show Tomcat on port 80 (`SERVER_PORT=80`).
- **TLS to Neon** (`PKIX path building failed` or a host-name error): the `verify-full` + `DefaultJavaSSLFactory` combination failed. Switch `DB_URL` to `sslmode=require` with the same JSON-file method as Task 2 Step 7, and record the error text and the reason in the ledger (spec section 12).
- **Flyway permission errors:** re-check Task 2 Step 5's output.

- [ ] **Step 3: Swagger over HTTPS**

```bash
curl -fsS $BASE/v3/api-docs | py -3.12 -c "import json,sys; print(json.load(sys.stdin)['servers'])"
curl -fsS -o /dev/null -w "%{http_code}\n" $BASE/swagger-ui/index.html
```

Expected: `[{'url': '/', 'description': 'This server'}]` and `200`. Ask Wrenz to open `$BASE/swagger-ui/index.html`, run **POST /api/auth/login** with the demo owner through "Try it out", and confirm the request URL Swagger shows starts with `https://` and returns 200.

- [ ] **Step 4: Note the time and stop sending requests**

Record the current time in the ledger. From now on, nobody opens the site until Step 6's idle sample has run (tell Wrenz).

- [ ] **Step 5: Live forged-header probe**

This locks this PC's IP out of logging in to the live app for 15 minutes. Run it once:

```bash
for i in $(seq 1 31); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 203.0.113.$i" -d "{\"email\":\"probe$i@probe.test\",\"password\":\"wrong-password\"}")
  echo "$i $code"
done
```

Expected: attempts 1 to 30 return `401`, attempt 31 returns `429`. That proves the limiter keys on the address Azure supplies, not the forged one. If attempt 31 returns `401`, **stop the milestone**: the live app is keying on a forged value. Record the output and report it to Wrenz before going further.

- [ ] **Step 6: Cold-start measurements**

(a) **Restart sample** (approximates a cold start):

```bash
az webapp restart --resource-group $RG --name $APP
start=$(date +%s)
until curl -fsS --max-time 60 $BASE/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
echo "restart to UP: $(( $(date +%s) - start )) s"
```

(b) **Idle samples** (the real case): run in the background so the session is not blocked, with no other requests to the site in between:

```bash
sleep 1560; curl -s -o /dev/null -w "idle sample: %{http_code} in %{time_total} s\n" --max-time 240 $BASE/actuator/health
```

Run it twice (the second time after another 26 quiet minutes). Record all three numbers. The decision uses the **slower** idle sample.

- [ ] **Step 7: Memory and CPU use**

```bash
WEBAPP_ID=$(az webapp show --resource-group $RG --name $APP --query id -o tsv)
MSYS_NO_PATHCONV=1 az monitor metrics list --resource "$WEBAPP_ID" --metric MemoryWorkingSet --interval PT5M --aggregation Maximum -o table
MSYS_NO_PATHCONV=1 az monitor metrics list --resource "$WEBAPP_ID" --metric CpuTime --interval PT1H --aggregation Total -o table
```

Record the peak memory and the CPU seconds used today. Ask Wrenz to open the plan's **Quotas** blade in the portal (App Service plan `plan-stockroom` → Quotas) and read **CPU (Day)** and **Bandwidth**; record them.

- [ ] **Step 8: Neon wake and suspend check**

Make one request (`curl -fsS $BASE/actuator/health`), then run in the background:

```bash
sleep 600; echo "ten minutes passed"
```

When it finishes, ask Wrenz to look at the Neon console (Branches → main → Computes) and say whether the compute shows **Idle/Suspended** (this checks `minimum-idle: 0`). Then:

```bash
curl -s -o /dev/null -w "neon wake: %{http_code} in %{time_total} s\n" --max-time 60 $BASE/actuator/health
```

Expected: `200` (the health check includes the database), typically within a second or two of Neon waking. Record the status Wrenz saw and the time.

- [ ] **Step 9: Write the ledger and decide**

Create `.superpowers/sdd/2026-10-12-phase2/progress.md` with the Write tool:

```markdown
# Phase 2 progress ledger

## M0: Free Azure deployment

- Branch: m0/deploy
- Azure account created: <date> (day-30 pay-as-you-go upgrade due: <date + 30 days>, or "existing subscription")
- Web app: <APP>.azurewebsites.net; Neon project stockroom (aws-ap-southeast-1, PG 16, max 0.25 CU)
- ProductionHttpTest mutation: with forward-headers-strategy commented out → <result>; restored → PASS
- TLS: sslmode=verify-full + DefaultJavaSSLFactory → <worked | failed: error text; fell back to require>
- Live forged-header probe: <1-30: 401, 31: 429 | other>
- Cold start: restart <n> s; idle sample 1 <n> s; idle sample 2 <n> s
- Memory peak: <n> MB; CPU today: <n> s; Quotas blade: CPU (Day) <n>, Bandwidth <n>
- Neon: compute status after 10 idle minutes = <Idle/Active>; wake request <n> s
- Decision (spec 3.5): slower idle sample <n> s → <no launcher | launcher page in M2 | STOP: raise Container Apps with Wrenz>
```

Apply spec section 3.5 to the slower idle sample:
- **15 s or less:** no launcher page.
- **Over 15 s, up to 60 s:** the M2 plan adds the launcher page (spec section 5.8).
- **Over 60 s, or any sample non-200, or the CPU (Day) quota near its limit:** stop and tell Wrenz; Container Apps is only reconsidered with his approval (spec section 3.5).

**CHECKPOINT:** show Wrenz the ledger numbers and the decision before Task 4.

---

### Task 4: Continuous deployment with GitHub OIDC, and CI runs once per change

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `RG`, `APP` (Task 2); the `app-jar` artifact the `verify` job already uploads on `main`.
- Produces: GitHub secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`; repository variable `AZURE_WEBAPP_NAME`; a `deploy` job that later milestones extend (M2 adds `web` and `e2e` to its `needs`).

- [ ] **Step 1: Check the action versions**

```bash
gh api repos/Azure/login/releases/latest --jq .tag_name
gh api repos/Azure/webapps-deploy/releases/latest --jq .tag_name
gh api repos/actions/download-artifact/releases/latest --jq .tag_name
```

Use each result's major version in Step 4 (the YAML below assumes `azure/login@v2`, `azure/webapps-deploy@v3`, `actions/download-artifact@v7`; change the tags if the majors differ).

- [ ] **Step 2: Create the deploy identity and its federated credential**

Create `C:/Users/OASIS/AppData/Local/Temp/stockroom-fic.json` with the Write tool:

```json
{
  "name": "github-main",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:WrenzLaylo/stockroom-api:ref:refs/heads/main",
  "audiences": ["api://AzureADTokenExchange"],
  "description": "GitHub Actions deploys from main"
}
```

Then:

```bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
set -a; . ./.env; set +a
SUB=$(az account show --query id -o tsv)
TENANT=$(az account show --query tenantId -o tsv)
CLIENT_ID=$(az ad app create --display-name stockroom-github-deploy --query appId -o tsv)
az ad sp create --id "$CLIENT_ID" -o none
WEBAPP_ID=$(az webapp show --resource-group $RG --name $APP --query id -o tsv)
MSYS_NO_PATHCONV=1 az role assignment create --assignee "$CLIENT_ID" --role "Website Contributor" --scope "$WEBAPP_ID" -o none
az ad app federated-credential create --id "$CLIENT_ID" --parameters "@C:/Users/OASIS/AppData/Local/Temp/stockroom-fic.json" -o none
rm -f /c/Users/OASIS/AppData/Local/Temp/stockroom-fic.json
az ad app federated-credential list --id "$CLIENT_ID" --query "[].subject" -o tsv
```

Expected: `repo:WrenzLaylo/stockroom-api:ref:refs/heads/main`. Add to the ledger: **if the repository is renamed (M6), this subject must be updated to the new name or deploys stop.**

- [ ] **Step 3: Store the IDs in GitHub**

```bash
gh secret set AZURE_CLIENT_ID --repo WrenzLaylo/stockroom-api --body "$CLIENT_ID"
gh secret set AZURE_TENANT_ID --repo WrenzLaylo/stockroom-api --body "$TENANT"
gh secret set AZURE_SUBSCRIPTION_ID --repo WrenzLaylo/stockroom-api --body "$SUB"
gh variable set AZURE_WEBAPP_NAME --repo WrenzLaylo/stockroom-api --body "$APP"
gh secret list --repo WrenzLaylo/stockroom-api
```

Expected: the three secret names listed.

- [ ] **Step 4: Replace `.github/workflows/ci.yml`**

Write the whole file with the Write tool:

```yaml
name: CI

on:
  # Runs once per change: a pull request gets the pull_request run, and main gets a push run (which can deploy).
  push:
    branches: [main]
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
      # The deploy job below ships THIS jar, the one the tests just passed on. It is never rebuilt.
      - name: Upload application jar
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v7
        with:
          name: app-jar
          path: target/stockroom-api.jar
          retention-days: 14

  deploy:
    needs: verify
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    # One deploy at a time; a queued run never cancels one that is mid-deploy.
    concurrency:
      group: production-deploy
      cancel-in-progress: false
    permissions:
      contents: read
      actions: read
      id-token: write   # a short-lived GitHub OIDC token for azure/login: no Azure password is stored anywhere
    steps:
      # If an older run gets here after a newer one, don't let it replace the newer release.
      - name: Skip if main has moved on
        id: fresh
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          latest=$(gh api "repos/${{ github.repository }}/commits/main" --jq .sha)
          if [ "$latest" = "${{ github.sha }}" ]; then
            echo "deploy=true" >> "$GITHUB_OUTPUT"
          else
            echo "deploy=false" >> "$GITHUB_OUTPUT"
            echo "main is now at $latest; skipping the older ${{ github.sha }}"
          fi
      - name: Download the jar this run tested
        if: steps.fresh.outputs.deploy == 'true'
        uses: actions/download-artifact@v7
        with:
          name: app-jar
          path: dist
      - name: Sign in to Azure with OIDC
        if: steps.fresh.outputs.deploy == 'true'
        uses: azure/login@v2
        with:
          client-id: ${{ secrets.AZURE_CLIENT_ID }}
          tenant-id: ${{ secrets.AZURE_TENANT_ID }}
          subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}
      - name: Deploy to Azure App Service
        if: steps.fresh.outputs.deploy == 'true'
        uses: azure/webapps-deploy@v3
        with:
          app-name: ${{ vars.AZURE_WEBAPP_NAME }}
          package: dist/stockroom-api.jar
      - name: Smoke test (allows for a cold start)
        if: steps.fresh.outputs.deploy == 'true'
        run: |
          for i in $(seq 1 30); do
            if curl -fsS --max-time 60 "https://${{ vars.AZURE_WEBAPP_NAME }}.azurewebsites.net/actuator/health" | grep -q '"status":"UP"'; then
              exit 0
            fi
            sleep 10
          done
          echo "Health did not report UP within about 5 minutes"
          exit 1
```

- [ ] **Step 5: Validate the YAML and commit**

```bash
py -3.12 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/ci.yml')); print(sorted(d['jobs']), d[True])"
git add .github/workflows/ci.yml
git commit -m "ci: deploy main to App Service with GitHub OIDC after the tests pass; run CI once per change"
```

Expected: `['deploy', 'verify'] {'push': {'branches': ['main']}, 'pull_request': None}` (PyYAML reads the `on:` key as `True`). If PyYAML is missing, run `py -3.12 -m pip install pyyaml` first.

The deploy job first runs after the PR is merged (Task 5 Step 6).

**Fallback, only if OIDC fails on the first run** (for example `AADSTS700213` or `AADSTS70021`, a subject mismatch): first compare the federated credential's subject with the error's claimed subject and fix it. If OIDC still cannot work on this account, switch to a publish profile and record why in the ledger:

```bash
az resource update --resource-group $RG --name scm --namespace Microsoft.Web --resource-type basicPublishingCredentialsPolicies --parent sites/$APP --set properties.allow=true
az webapp deployment list-publishing-profiles --resource-group $RG --name $APP --xml | gh secret set AZURE_WEBAPP_PUBLISH_PROFILE --repo WrenzLaylo/stockroom-api
```

Then remove the `azure/login` step and the `id-token` permission, and add `publish-profile: ${{ secrets.AZURE_WEBAPP_PUBLISH_PROFILE }}` to the `azure/webapps-deploy` step.

---

### Task 5: README, pull request, review, first automatic deploy, resume line

**Files:**
- Modify: `README.md` (intro line, "Try it", a new "Deployment" section, "Known limits")
- Modify (outside the repo): `C:\Users\OASIS\Downloads\Wrenz_Laylo_Rocket_Cloud_Developer_Resume.txt` and its `.docx`/`.pdf`
- Modify (gitignored): `.superpowers/sdd/2026-10-12-phase2/progress.md`

**Interfaces:**
- Consumes: the measured numbers (Task 3), `APP` (Task 2).
- Produces: a live, documented demo link; the merged M0 branch.

- [ ] **Step 1: Update the README**

Make these four edits (replace `<APP>`, `<N>` with the real web app name and the slower idle sample rounded to the nearest 5 seconds):

1. The intro sentence ending `and designed for **Azure** (App Service + Azure Database for PostgreSQL).` becomes:
   `and deployed on **Azure App Service** with a **Neon** serverless PostgreSQL database, on free tiers.`
2. In "Try it", the line `- **Live Swagger UI:** see "Live demo" below (added once deployed)` becomes:
   `- **Live Swagger UI:** https://<APP>.azurewebsites.net/swagger-ui/index.html (free tier: if nobody has used it for 20 minutes, the first request takes about <N> seconds while the app starts)`
3. Add this section directly before `## Known limits (deliberate for Phase 1)`:

```markdown
## Deployment

- **Hosting, $0 a month:** Azure App Service **F1** (Linux, Java 21, Southeast Asia) and **Neon Free** PostgreSQL 16 in Singapore. Every Azure resource is a free SKU that cannot bill; a $1 budget alert watches the subscription anyway.
- **CI/CD:** every pull request runs the full test suite against PostgreSQL. A merge to `main` runs it again, then the `deploy` job ships that exact jar to App Service, signing in with a short-lived GitHub OIDC token (no stored Azure password), and smoke-tests `/actuator/health`.
- **Database access:** the app connects over TLS as `stockroom_app`, a role that owns only its own schema (`ops/create-app-role.sql`).
- **Forwarded headers are off on purpose:** on Azure, Spring Boot would otherwise let Tomcat rewrite `X-Forwarded-For`, and a forged entry could dodge the login rate limit. `ProductionHttpTest` runs the real server as if on Azure to prove it.
- **Demo reset:** `ops/reset-demo.sql`, then restart the app to re-seed.
```

4. In "Known limits", the line `- **Azure tier:** see "Live demo" for the tier in use and what it means for response time.` becomes:
   `- **Free tier:** F1 unloads the app after 20 idle minutes (first request about <N> s), and allows 60 CPU minutes and 165 MB of traffic a day; past either, the app pauses until 8 AM Manila time. Neon sleeps after 5 idle minutes and wakes in about a second.`

- [ ] **Step 2: Commit and push**

```bash
git add README.md
git commit -m "docs: live demo link, deployment section and free-tier limits"
git push -u origin m0/deploy
```

- [ ] **Step 3: Open the pull request**

Write the body to `C:/Users/OASIS/AppData/Local/Temp/m0-pr.md` with the Write tool (measured numbers filled in; no `Co-Authored-By`, no "Generated with" line):

```markdown
Milestone M0 of Phase 2 (spec sections 3, 9.2, 9.3): today's API, live and free.

- **Live:** https://<APP>.azurewebsites.net/swagger-ui/index.html
- **Hosting:** App Service F1 + Neon Free, $0/month, $1 budget alert.
- **Security fix:** `server.forward-headers-strategy: none`. On Azure, Spring Boot would otherwise enable Tomcat's RemoteIpValve, which rewrites `X-Forwarded-For` so a forged entry ends up last and dodges the login IP limit. `ProductionHttpTest` proves it with the real server (and fails when the setting is removed).
- **CD:** a `deploy` job ships the jar the tests passed on, via GitHub OIDC, then smoke-tests health. CI now runs once per change.
- **Measured:** cold start <n> s (slower idle sample), memory peak <n> MB, Neon wake <n> s, live forged-header probe 401 ×30 then 429.
- **Decision:** <no launcher page | launcher page goes into M2>.

Tests: 116 passing.
```

```bash
gh pr create --repo WrenzLaylo/stockroom-api --base main --head m0/deploy \
  --title "M0: free Azure deployment (App Service F1 + Neon)" --body-file C:/Users/OASIS/AppData/Local/Temp/m0-pr.md
```

- [ ] **Step 4: Code review on Fable 5.1 (spec section 10.2)**

Dispatch a fresh reviewer with `model: "fable"` on the PR diff (`gh pr diff <number> --repo WrenzLaylo/stockroom-api`), with the spec's section 3 as the requirement. Tell it: read-only, never kill processes by name, report Blocking / Should change / Nice to have with file and line. Verify each finding against the code before changing anything, fix the accepted ones on the branch with a test where behaviour changes, push, and note every ruling in the ledger.

- [ ] **Step 5: Wrenz merges**

Tell Wrenz the PR is ready and wait for him to merge it.

- [ ] **Step 6: Watch the first automatic deploy**

```bash
gh run list --repo WrenzLaylo/stockroom-api --branch main --limit 1
gh run watch --repo WrenzLaylo/stockroom-api $(gh run list --repo WrenzLaylo/stockroom-api --branch main --limit 1 --json databaseId --jq '.[0].databaseId') --exit-status
```

Expected: both `verify` and `deploy` succeed, and the deploy log shows the "Skip if main has moved on" step set `deploy=true` and the smoke test exited 0. Then confirm the live app answers:

```bash
curl -fsS https://$APP.azurewebsites.net/actuator/health
```

If the deploy job fails at `azure/login`, apply the fallback in Task 4 Step 5 on a new branch `m0/deploy-fix` with its own PR.

- [ ] **Step 7: Update the Rocket resume**

In `C:\Users\OASIS\Downloads\Wrenz_Laylo_Rocket_Cloud_Developer_Resume.txt`, the Stockroom bullet that starts `- Each shop's data is kept separate` keeps its text and gains one new bullet after it:

```
- Deployed on Azure App Service with a Neon PostgreSQL database; GitHub Actions runs the tests and deploys every merge to main.
```

Rebuild and check it is still one page:

```bash
cd /c/Users/OASIS/Downloads
py -3.12 _build_onepage_resume_docx.py Wrenz_Laylo_Rocket_Cloud_Developer_Resume.txt
py -3.11 _docx2pdf.py Wrenz_Laylo_Rocket_Cloud_Developer_Resume.docx
```

Expected: `pages: 1`.

- [ ] **Step 8: Close out the ledger**

Append to `.superpowers/sdd/2026-10-12-phase2/progress.md`: the PR number, the review rulings, the merge commit, the first automatic deploy's run ID and result, and `M0: complete`.
