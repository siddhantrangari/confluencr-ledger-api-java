# Design decisions

Short write-ups of the choices that matter, what I rejected, and why.

## 1. Stack: Java 21 + Spring Boot 3.3 + JdbcTemplate + PostgreSQL 16 + Flyway

- **PostgreSQL over a NoSQL store.** A ledger needs multi-row ACID transactions, row-level locks and declarative constraints (`CHECK`, `UNIQUE`, `FOREIGN KEY`, triggers). Postgres provides all of these natively. A document store would push those guarantees into application code, where they are much harder to prove.
- **JdbcTemplate with plain SQL, not JPA/Hibernate.** Correctness here depends on exactly when `SELECT … FOR UPDATE`, `INSERT`, `UPDATE` and `COMMIT` run. With JdbcTemplate every statement is visible in the two repository classes. With JPA the persistence context, dirty checking and flush timing decide when SQL runs. That makes it easy to read a stale balance into an entity, change it in Java and write it back (a lost update), or to flush later than you think. `@Lock(PESSIMISTIC_WRITE)` would also work, but the application has only about 10 SQL statements, so an ORM adds more machinery than it saves.
- **Flyway** applies `V1__init.sql` on startup. The schema is versioned with the code, and tests run the exact same migration.
- **Spring MVC (blocking), not WebFlux.** Each request holds one pooled connection inside one `@Transactional` method. Correctness comes from Postgres locks, not from the threading model, so the simpler blocking stack is the right trade. JDBC is blocking anyway.
- **Records for requests, responses and rows.** They are immutable, short, and Jackson and Bean Validation support them directly.
- **springdoc-openapi.** The spec is generated from the same records and validation annotations that run, so the docs can't drift from the code. Swagger UI is at `/swagger-ui`.

## 2. Money type

`NUMERIC(18,2)` in the database and `BigDecimal` in Java. Amounts are returned as JSON **strings**.

- **`double` was rejected.** Binary floating point can't represent 0.1 exactly. A test checks that `0.1 + 0.2` is exactly `"0.30"`. Jackson reads a JSON number directly into `BigDecimal` from its text, so no `double` is involved even when the client sends `0.1` as a number.
- **Integer minor units** (paise in a `long`) are equally correct. They push unit conversion onto every client, though (`10000` means ₹100), and the brief's examples are in major units.
- **Strings in JSON**, because many JSON parsers (JavaScript included) read numbers as IEEE-754 doubles. One Jackson serializer in `JacksonConfig` writes every `BigDecimal` with `toPlainString()`, so this is a single rule instead of an annotation on every field.
- **Validation:** `@ValidAmount` (`> 0`, `<= 1,000,000,000.00`, at most 2 decimals) is one composed annotation shared by transactions and transfers. After validation, `Money.normalize` sets the scale to 2 with `RoundingMode.UNNECESSARY`. Validation guarantees nothing needs rounding, so that mode can never round silently; it would throw instead.
- **BigDecimal comparisons use `compareTo`**, never `equals`, because `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is false.

## 3. Concurrency and locking

**The problem:** two debits of 60 against a balance of 100. Without coordination, both read 100, both pass the check, and the balance ends at −20. That is a lost update and an overdraw. Two copies of the same request arriving at the same moment have the same race.

**The solution: a pessimistic row lock on the account.** Each write is one `@Transactional` service method:

```
BEGIN                                                          -- @Transactional starts
  SELECT … FROM accounts WHERE id = ? FOR UPDATE               -- other writers for this account wait here
  SELECT … FROM transactions WHERE account_id = ? AND reference_id = ?   -- duplicate?
  (debit) check balance >= amount                              -- in Java, on the locked row
  INSERT INTO transactions … RETURNING …
  UPDATE accounts SET balance = balance + ? … RETURNING balance -- relative update, computed by Postgres
COMMIT                                                         -- or ROLLBACK on any RuntimeException
```

- The lock serialises writers **per account**. Different accounts never block each other.
- Isolation is **READ COMMITTED** (the Postgres default), and that is enough. When a waiting transaction gets the lock, `FOR UPDATE` returns the *latest committed* row. Each later statement takes a fresh snapshot, so the duplicate check sees a row committed by the transaction it waited for.
- **`@Transactional` details that matter:**
  - Spring's proxy only intercepts calls from outside the bean. That is why the locking, checks and writes live in one public service method, called from the controller.
  - Spring rolls back on unchecked exceptions by default. Every domain error (`ApiException`) is a `RuntimeException`, so a rejected request never commits anything.
  - The lock must be taken inside the transaction. Outside one, the connection is in auto-commit mode and the lock is released as soon as the `SELECT` finishes.
- **Alternatives considered:**
  - *A conditional update* (`UPDATE … SET balance = balance - ? WHERE id = ? AND balance >= ?`) is also correct for a single debit. It doesn't cover the "check duplicate, then insert, then update" sequence as clearly, though.
  - *SERIALIZABLE isolation* is correct but aborts conflicting transactions, so every caller needs a retry loop. Under contention on one hot account it would mostly retry.
  - *Optimistic locking* (`@Version` column) suits low contention, and it also needs a retry loop.
  - *`synchronized` or a Java lock* only works inside one JVM. With two instances behind a load balancer it protects nothing. The database lock works across any number of instances.
- **Defence in depth:** even if the locking code were removed, `CHECK (balance >= 0)` and `UNIQUE (account_id, reference_id)` still make Postgres refuse the bad write. `GlobalExceptionHandler` maps those errors to 422 and 409.

**Transfers** lock two accounts. If A→B and B→A start at the same moment, each could hold one lock and wait for the other: a deadlock. To prevent that, the two account IDs are **always locked in sorted order** (by their string form, which is the order Postgres sorts UUIDs in), so both transfers queue on the same first lock. A test runs 20 opposite-direction transfers in parallel and checks that there are no deadlocks and that the total amount of money is unchanged. Removing the sort makes that test fail with a real Postgres deadlock (see docs/TESTING.md).

`created_at` uses `clock_timestamp()` (time of the INSERT, which happens after the lock is acquired), not `now()` (time the transaction started). Per account, history order therefore matches the order in which transactions were actually applied, even when a request waited for the lock.

## 4. Idempotency (`reference_id`)

- `UNIQUE (account_id, reference_id)` in the schema is the source of truth. The same reference on two different accounts is fine.
- The service checks for a duplicate **inside the lock, before the balance check**. Retrying a debit that already succeeded therefore returns "already exists", even if the balance has dropped since.
- **409 vs 200.** The brief lists "duplicate transaction" under error handling and asks for a response "indicating that the transaction already exists". A 409 says that unambiguously. The original transaction is in `details.existing_transaction`, so a client retrying after a network timeout still gets the result of its first attempt. (The Stripe style, 200 with a replayed response, is the other common convention, and switching to it is a small change.)
- Rejected requests store nothing, so their `reference_id` stays reusable. See ASSUMPTIONS #15.

## 5. Immutability

- **API:** there are no PUT, PATCH or DELETE routes for transactions, and `TransactionRepository` has no update or delete method. Such requests return 404 or 405 in the standard error shape.
- **Database:** a `BEFORE UPDATE OR DELETE` trigger on `transactions` raises an exception. This protects the history from a future bug, a manual SQL session or a script, not only from the API. Corrections are made the way accountants make them: with a new, compensating transaction.
- `accounts.balance` is the only mutable money field. It changes in exactly one method (`AccountRepository.applyBalanceChange`), always in the same transaction as the INSERT that justifies it.

## 6. Atomicity

Each write runs in one `@Transactional` method: Spring takes a connection, turns off auto-commit, and then commits, or rolls back if a `RuntimeException` escapes.

The tests wrap the real `AccountRepository` in a Mockito `@SpyBean` and make it throw **between** the INSERT and the balance UPDATE, and between the two legs of a transfer. In both cases nothing persists, the client gets a 500 that says nothing changed, and the same `reference_id` can be retried.

## 7. Errors

There is one error shape everywhere: `{"error": {"code", "message", "details", "request_id"}}`. Clients branch on `code`, which is stable, and show `message`. `request_id` is taken from the `X-Request-ID` header if present (otherwise generated) by `RequestIdFilter`. It is echoed in the response header and put in the logging MDC.

All mapping happens in one `@RestControllerAdvice`, which handles:
- our `ApiException` subclasses
- Bean Validation failures (`MethodArgumentNotValidException` for bodies, `HandlerMethodValidationException` for query parameters)
- Jackson failures (`HttpMessageNotReadableException`)
- type mismatches, unknown routes, wrong methods and media types
- database backstops
- a final catch-all `Exception` → 500

| Status | When |
|---|---|
| 400 | Request is malformed or invalid (`VALIDATION_ERROR`, `INVALID_REQUEST_BODY`, `INVALID_AMOUNT`, `INVALID_TRANSACTION_TYPE`, `INVALID_PAGINATION`, `SAME_ACCOUNT_TRANSFER`) |
| 404 | Account (or route) doesn't exist |
| 409 | Duplicate `reference_id` |
| 415 | Body isn't `application/json` |
| 422 | Valid request that breaks a business rule (`INSUFFICIENT_BALANCE`, `BALANCE_LIMIT_EXCEEDED`) |
| 500 | Unexpected; the transaction was rolled back |

**Strict JSON.** Unknown fields are rejected (`fail-on-unknown-properties`), so `{"name": "A", "balance": 500}` is a 400, not a silently ignored field. Jackson's default of turning `123` or `true` into a string is switched off for text fields, so a wrong JSON type is reported instead of accepted.

## 8. Pagination

Offset pagination (`page`, `limit`) because the brief asks for page numbers and total pages. The history method is `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, so the existence check, `COUNT(*)` and the page query all read one snapshot, and `total_records` always matches the rows returned. The index `(account_id, created_at DESC, seq DESC)` serves the query directly.

## 9. Testing strategy

Tests run against a real PostgreSQL 16 started by **Testcontainers**, not H2 or mocks. What needs proving (row locks, constraints, triggers, rollback, `clock_timestamp()`) only exists in Postgres, and H2 behaves differently for all of them. One container is shared by the whole run, and the tables are truncated before each test. Coverage:

- every rule and every error case in the brief, through the HTTP layer (MockMvc)
- **concurrency**: 20 threads calling the `@Transactional` services directly, each on its own pooled connection, released together by a `CyclicBarrier`. They cover parallel debits that would overdraw, parallel credits that would lose updates, 20 identical duplicates, opposite-direction transfers, and parallel transfers draining one source
- **atomicity**: failures injected mid-operation with a Mockito spy
- **constraints and triggers**, tested directly with SQL that bypasses the API

Proof the tests catch real bugs: four deliberate code mutations each make the suite fail (docs/TESTING.md). `mvn verify` also enforces at least 90% line coverage with JaCoCo.

## Next steps (deliberately out of scope)

- **Auth and multi-tenancy:** Spring Security with JWT and account ownership checks. Right now any caller can act on any account.
- **Request fingerprinting:** store a hash of the request with each `reference_id` and return 422 when a reused key arrives with a different payload, instead of treating it as a duplicate.
- **Audit log of rejected attempts:** failed and declined transactions recorded in a separate table, for support and fraud analysis.
- **Double-entry ledger:** every movement becomes balanced entries across accounts (including system accounts for deposits and withdrawals), so `SUM(all entries) = 0` holds globally.
- **Hot accounts:** a single very busy account serialises on its row lock. Options at scale include sharded sub-balances, batching, or an append-only event log with an asynchronously derived balance.
- **Outbox and events:** write an event row in the same transaction and publish it to Kafka or RabbitMQ for notifications and analytics, without dual-write inconsistency.
- **Cursor pagination:** `(created_at, seq)` keyset pagination for deep pages and stable paging while new rows arrive.
- **Ops:** Spring Boot Actuator health and metrics (p95 latency, lock wait time, Hikari pool usage), structured JSON logs, rate limiting, a lock timeout (`SET lock_timeout`) so a stuck transaction can't hold up an account forever, and a least-privilege DB role without UPDATE/DELETE/TRUNCATE on `transactions`.
