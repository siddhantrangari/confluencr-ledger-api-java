# Transaction Ledger API

A backend API for a simple transaction ledger. It supports accounts, credit and debit transactions with idempotent `reference_id`s, a filterable and paginated history, and atomic money transfers (the bonus).

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · JdbcTemplate (plain SQL, no ORM) · JUnit 5 + Testcontainers · springdoc-openapi

**What it guarantees, and how:**

| Guarantee | Enforced by |
|---|---|
| A balance never goes negative, even under concurrent debits | Row lock (`SELECT … FOR UPDATE`) and `CHECK (balance >= 0)` |
| A `reference_id` is used at most once per account, even with parallel retries | Duplicate check under the lock and `UNIQUE (account_id, reference_id)` |
| Inserting a transaction and updating the balance happen together or not at all | One `@Transactional` method per operation |
| Transactions can never be edited or deleted | No such routes or repository methods, plus a DB trigger that rejects `UPDATE`/`DELETE` |
| Transfers never deadlock and are never partial | Both accounts locked in sorted order, inside one transaction |
| No rounding errors | `NUMERIC(18,2)` and `BigDecimal`; amounts returned as strings |

## Run it

Prerequisites: **Docker**. For the local (non-Docker) run and the tests, also **Java 21**. Maven is not needed, because `./mvnw` downloads it.

**Everything in Docker (one command):**

```bash
docker compose up --build
```

The API runs on **http://localhost:8080**. Flyway creates the schema on startup. Open **http://localhost:8080/swagger-ui** to try every endpoint.

**Or run the API locally against the Postgres container:**

```bash
docker compose up -d db          # Postgres on host port 5434
./mvnw spring-boot:run           # API on http://localhost:8080
```

Configuration uses the standard Spring properties. The defaults in `application.yml` match `docker-compose.yml`. Override with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`.

## Run the tests

```bash
./mvnw verify      # 119 tests + JaCoCo coverage gate (>= 90% lines); ~15 s once dependencies are cached
```

The tests start their own PostgreSQL 16 container with **Testcontainers**, so Docker must be running, but nothing else is needed. They include parallel tests with 20 concurrent connections each.

End-to-end check against the running Docker stack:

```bash
scripts/smoke-test.sh      # 24 HTTP checks, every endpoint and the main error cases
```

**Evidence:** [docs/TESTING.md](docs/TESTING.md) has the test results, coverage numbers, the mutation checks (four mechanisms removed on purpose, and the tests caught every one) and the smoke-test output.

> **Docker Desktop alternatives (Colima, Rancher):** if Testcontainers reports "Could not find a valid Docker environment", point it at your socket. For Colima:
> `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`

## Project layout

```
src/main/java/com/confluencr/ledger/
  api/            controllers (HTTP only: validate -> call service -> return) and request/response records
  service/        TransactionService (lock -> duplicate check -> balance check -> insert -> update), TransferService, AccountService
  repository/     AccountRepository, TransactionRepository: every SQL statement, via JdbcTemplate
  model/          Account, Transaction, Balance records (table rows, also used as responses)
  error/          ApiException subclasses (status + stable code) and GlobalExceptionHandler (one error shape)
  validation/     @ValidAmount: > 0, <= 1,000,000,000.00, at most 2 decimals
  config/         Jackson (money as strings, strict JSON) and OpenAPI setup
  web/            RequestIdFilter (X-Request-ID)
src/main/resources/db/migration/V1__init.sql   tables, constraints, index, immutability trigger
src/test/java/...  API, integrity, atomicity, concurrency and constraint tests (Testcontainers)
scripts/smoke-test.sh   end-to-end HTTP check against a running instance
docs/API.md             endpoint reference with examples and every error code
docs/openapi.json       OpenAPI 3 spec (also served live at /v3/api-docs)
docs/TESTING.md         test evidence: results, coverage, mutation checks, smoke test
ASSUMPTIONS.md          every ambiguity in the brief and the decision taken
DECISIONS.md            design trade-offs: money type, locking, idempotency, immutability, next steps
```

## Documentation

- **[docs/API.md](docs/API.md)**: endpoints, request and response examples, and the full error-code table
- **[ASSUMPTIONS.md](ASSUMPTIONS.md)**: how each open question in the brief was resolved
- **[DECISIONS.md](DECISIONS.md)**: why it's built this way, the alternatives considered, and next steps
- **[docs/TESTING.md](docs/TESTING.md)**: proof that it works

## Requirements → tests

| Brief requirement | Test(s) |
|---|---|
| Account: unique system ID, name required, balance starts at 0, client can't set balance | `AccountApiTest` |
| Credit adds, debit subtracts | `creditIncreasesBalance`, `debitDecreasesBalance` |
| Debit with insufficient balance rejected, balance unchanged | `debitWithInsufficientBalanceIsRejected` |
| Duplicate `reference_id`: no new row, balance unchanged, "already exists" response | `duplicateReferenceCreatesNothingAndReturnsExisting` |
| Same `reference_id` allowed on another account | `sameReferenceAllowedOnDifferentAccounts` |
| Balance = result of all valid transactions (0 +100 +50 −30 −20 = 100) | `balanceEqualsSumOfValidTransactions` |
| Balance endpoint and 404 | `getAccountAndBalance`, `balanceOfUnknownAccountIs404` |
| History: type filter, pagination metadata, latest first | `HistoryApiTest` |
| Invalid type / amount / pagination / body / account ID | `invalidType`, `invalidAmount`, `invalidPagination`, `malformedJsonBody`, `malformedAccountIdIs400`, … |
| Immutability | `noApiRouteCanModifyTransactions`, `databaseRejectsUpdateOfATransaction`, `databaseRejectsDeleteOfATransaction` |
| Atomicity | `failureAfterInsertPersistsNothing`, `failureBetweenLegsRollsBackWholeTransfer` |
| Consistency under concurrency | `ConcurrencyTest` (overdraw, lost update, duplicate race, deadlock) |
| Transfer: moves money, needs balance, both accounts exist, never partial | `TransferApiTest`, `AtomicityTest` |
