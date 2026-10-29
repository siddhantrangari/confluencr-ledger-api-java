package com.confluencr.ledger;

import com.confluencr.ledger.api.CreateTransactionRequest;
import com.confluencr.ledger.api.CreateTransferRequest;
import com.confluencr.ledger.error.ApiException;
import com.confluencr.ledger.error.DuplicateTransactionException;
import com.confluencr.ledger.error.InsufficientBalanceException;
import com.confluencr.ledger.service.TransactionService;
import com.confluencr.ledger.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads hitting the same account at the same moment.
 *
 * The services are called directly (not through MockMvc) so each call is a real @Transactional
 * method on its own pooled connection, truly in parallel. A CyclicBarrier releases all workers
 * at once to maximise contention. Only domain errors (ApiException) are expected; anything else,
 * such as a Postgres deadlock, fails the test.
 */
class ConcurrencyTest extends IntegrationTest {

    private static final int WORKERS = 20;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransferService transferService;

    /** Runs every job on its own thread, all starting together. Returns each result or the ApiException it threw. */
    private List<Object> runConcurrently(int count, IntFunction<Callable<Object>> jobFactory) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(count);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Object> job = jobFactory.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        return job.call();
                    } catch (ApiException e) {
                        return e;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS)); // rethrows anything that isn't an ApiException
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Object> txn(String accountId, String type, String amount, String ref) {
        var request = new CreateTransactionRequest(type, new BigDecimal(amount), ref, null);
        return () -> transactionService.create(UUID.fromString(accountId), request);
    }

    private Callable<Object> transfer(String from, String to, String amount, String ref) {
        var request = new CreateTransferRequest(UUID.fromString(from), UUID.fromString(to), new BigDecimal(amount), ref, null);
        return () -> transferService.transfer(request);
    }

    private static long countOf(List<Object> results, Class<?> type) {
        return results.stream().filter(type::isInstance).count();
    }

    private static long successes(List<Object> results) {
        return results.stream().filter(r -> !(r instanceof Exception)).count();
    }

    @Test
    void parallelDebitsNeverOverdraw() throws Exception {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "seed");
        // 20 debits of 10 against a balance of 100: exactly 10 may succeed.
        List<Object> results = runConcurrently(WORKERS, i -> txn(acc, "debit", "10", "d" + i));
        assertThat(successes(results)).isEqualTo(10);
        assertThat(countOf(results, InsufficientBalanceException.class)).isEqualTo(10);
        assertThat(assertLedgerConsistent(acc)).isEqualByComparingTo("0");
    }

    @Test
    void parallelCreditsLoseNoUpdates() throws Exception {
        String acc = createAccount();
        List<Object> results = runConcurrently(WORKERS, i -> txn(acc, "credit", "1.25", "c" + i));
        assertThat(successes(results)).isEqualTo(WORKERS);
        assertThat(assertLedgerConsistent(acc)).isEqualByComparingTo(new BigDecimal("1.25").multiply(BigDecimal.valueOf(WORKERS)));
    }

    @Test
    void parallelMixedTrafficKeepsBalanceConsistent() throws Exception {
        String acc = createAccount();
        postTxn(acc, "credit", 50, "seed");
        runConcurrently(WORKERS, i -> txn(acc, i % 2 == 1 ? "credit" : "debit", "7", "m" + i));
        assertLedgerConsistent(acc);
    }

    @Test
    void parallelDuplicateSubmissionsCreateExactlyOneTransaction() throws Exception {
        String acc = createAccount();
        List<Object> results = runConcurrently(WORKERS, i -> txn(acc, "credit", "10", "same-ref"));
        assertThat(successes(results)).isEqualTo(1);
        assertThat(countOf(results, DuplicateTransactionException.class)).isEqualTo(WORKERS - 1);
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(1);
        assertThat(assertLedgerConsistent(acc)).isEqualByComparingTo("10");
    }

    /**
     * A->B and B->A at the same time is the classic deadlock; sorted lock order prevents it.
     * A deadlock would surface as a Spring DataAccessException (not an ApiException) and fail the test.
     */
    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        String a = createAccount("A");
        String b = createAccount("B");
        postTxn(a, "credit", 1000, "seed");
        postTxn(b, "credit", 1000, "seed");

        List<Object> results = runConcurrently(WORKERS,
                i -> i % 2 == 1 ? transfer(a, b, "5", "ab" + i) : transfer(b, a, "5", "ba" + i));

        assertThat(successes(results)).isEqualTo(WORKERS);
        BigDecimal total = assertLedgerConsistent(a).add(assertLedgerConsistent(b));
        assertThat(total).isEqualByComparingTo("2000"); // money is neither created nor destroyed
    }

    @Test
    void parallelTransfersNeverOverdrawSource() throws Exception {
        String a = createAccount("A");
        String b = createAccount("B");
        postTxn(a, "credit", 30, "seed");

        List<Object> results = runConcurrently(WORKERS, i -> transfer(a, b, "10", "t" + i));

        assertThat(successes(results)).isEqualTo(3);
        assertThat(assertLedgerConsistent(a)).isEqualByComparingTo("0");
        assertThat(assertLedgerConsistent(b)).isEqualByComparingTo("30");
    }

    @Test
    void workersReallyOverlap() throws Exception {
        // Sanity check for the harness itself: all workers are in flight at the same time.
        List<Object> threads = runConcurrently(WORKERS, i -> () -> Thread.currentThread().getName());
        assertThat(IntStream.range(0, WORKERS).mapToObj(threads::get).distinct().count()).isEqualTo(WORKERS);
    }
}
