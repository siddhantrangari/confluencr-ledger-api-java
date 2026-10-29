# Testing and evidence

Four kinds of evidence back the guarantees in the README:

1. **Automated suite.** 119 tests against a real PostgreSQL 16 (Testcontainers).
2. **Coverage gate.** JaCoCo, enforced by `mvn verify`.
3. **Mutation checks.** Each core safety mechanism was removed on purpose, to show the tests catch it.
4. **End-to-end smoke test.** Real HTTP against the Docker image built by `docker compose up`.

The numbers below are from a local run on 29 Oct 2026 (macOS, Docker 29 on Colima, Java 21). CI repeats steps 1 and 2 on every push and uploads the test and coverage reports as a build artifact.

## 1. Automated suite: `./mvnw verify`

```
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.AtomicityTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.IntegrityTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.AccountApiTest
[INFO] Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.ConcurrencyTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.TransferApiTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.HistoryApiTest
[INFO] Tests run: 47, Failures: 0, Errors: 0, Skipped: 0 -- in com.confluencr.ledger.TransactionApiTest
[INFO] Tests run: 119, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

The two `ERROR … unhandled error` log lines printed during the run are expected. They come from `AtomicityTest`, which deliberately crashes an operation halfway to prove the rollback.

| Test class | What it proves |
|---|---|
| `AccountApiTest` | Accounts: system-generated id, balance starts at 0, the client can't set balance, id or created_at, name rules, malformed JSON, 404 vs 400 for IDs, error shape, `X-Request-ID`, unknown route, `/health`, OpenAPI spec shape |
| `TransactionApiTest` | Credit adds and debit subtracts; insufficient balance leaves no row; the brief's example (0 +100 +50 −30 −20 = 100); duplicate returns 409 with the original; duplicate is checked before balance; same reference on other accounts; rejected reference reusable; case-sensitive reference; every invalid type, amount and reference; exact decimals (0.1 + 0.2 = 0.30); balance limit; 415 |
| `HistoryApiTest` | Latest first; pagination metadata; every page exactly once; page past the end; type filter; empty history; isolation between accounts; every invalid pagination value; invalid filter; 404 |
| `TransferApiTest` | Money moves; legs linked by `transfer_id`; both legs in history; insufficient balance changes nothing; both accounts must exist; self-transfer; duplicate transfer; reference already used on the destination; validation |
| `IntegrityTest` | No API route can modify a transaction; the DB trigger rejects UPDATE and DELETE; the DB rejects a negative balance, duplicate reference, bad type or amount, missing account, and deleting an account with history |
| `AtomicityTest` | A crash between INSERT and balance UPDATE, or between the two transfer legs, leaves nothing behind, and the reference can be retried |
| `ConcurrencyTest` | 20 threads at once: overdraw impossible (exactly 10 of 20 debits succeed); no lost credits; mixed traffic stays consistent; 20 identical requests create exactly 1 row; opposite transfers don't deadlock and conserve money; parallel transfers can't overdraw |

After any test that changes money, `assertLedgerConsistent` checks the core invariant directly in SQL: stored balance = SUM(credits) − SUM(debits), and balance ≥ 0.

## 2. Coverage: JaCoCo

`./mvnw verify` writes `target/site/jacoco/index.html` and **fails the build below 90% line coverage**.

| Metric | Covered |
|---|---|
| Lines | 95.5% |
| Instructions | 95.0% |
| Branches | 78.8% |

The uncovered lines are mostly the database backstop handlers in `GlobalExceptionHandler` (for example a `UniqueViolation` that reaches the controller). They are unreachable through the API by design, because the service layer catches those cases first. Their database side is tested directly in `IntegrityTest`. The other uncovered lines are `LedgerApplication.main` and one rethrow line in `AccountRepository`.

## 3. Mutation checks: do the tests catch real bugs?

Each mechanism was removed on purpose, the full suite was run, and the code was restored with `git checkout`.

| Mutation | Result | Tests that failed |
|---|---|---|
| Remove `FOR UPDATE` from `AccountRepository.lockById` | 4 errors | `parallelDebitsNeverOverdraw`, `parallelDuplicateSubmissionsCreateExactlyOneTransaction`, `parallelTransfersNeverOverdrawSource`, `oppositeTransfersDoNotDeadlockAndConserveMoney` (Postgres reported a deadlock) |
| Lock transfer accounts in request order instead of sorted order | 1 error | `oppositeTransfersDoNotDeadlockAndConserveMoney`: a real Postgres deadlock |
| Run the balance check before the duplicate check | 1 failure | `duplicateIsReportedBeforeInsufficientBalance` |
| Remove `@Transactional` from `TransactionService.create` | 2 failures, 2 errors | `failureAfterInsertPersistsNothing`, `parallelDebitsNeverOverdraw`, `parallelDuplicateSubmissionsCreateExactlyOneTransaction`, `balanceLimitIsEnforced` |

`parallelCreditsLoseNoUpdates` still passes without `FOR UPDATE`, because the relative `UPDATE … SET balance = balance + ?` is atomic in Postgres on its own. The lock is needed for the *check-then-write* cases: overdraw and duplicates.

## 4. End-to-end smoke test against Docker

```bash
docker compose up -d --build
scripts/smoke-test.sh            # 24 passed, 0 failed
```

The script calls every endpoint and the main error cases over real HTTP against the container: health, accounts, credits, the duplicate, debits, insufficient balance, invalid type, amount and account, the brief's balance example (100.00), a transfer, an insufficient transfer, a self-transfer, filtered and paginated history, invalid pagination, DELETE rejected, the OpenAPI spec and Swagger UI. The full output of the run is in [smoke-test-output.txt](smoke-test-output.txt).

## Reproduce

```bash
./mvnw verify                        # tests + coverage gate (Docker must be running)
open target/site/jacoco/index.html   # coverage report
docker compose up -d --build && scripts/smoke-test.sh
```
