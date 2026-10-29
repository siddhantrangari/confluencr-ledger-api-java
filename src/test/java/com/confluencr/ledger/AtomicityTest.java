package com.confluencr.ledger;

import com.confluencr.ledger.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Simulates a crash in the middle of an operation, after a row has been inserted but before
 * the work is finished. The @Transactional rollback must leave nothing behind.
 */
class AtomicityTest extends IntegrationTest {

    /** The real repository, wrapped so a test can make one call fail. Reset after every test. */
    @SpyBean
    private AccountRepository accounts;

    @Test
    void failureAfterInsertPersistsNothing() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "r1");

        // The transaction row is inserted, then the balance update blows up.
        doThrow(new RuntimeException("simulated failure mid-operation"))
                .when(accounts).applyBalanceChange(any(), any());
        Res res = postTxn(acc, "credit", 50, "r2");

        assertThat(res.status()).isEqualTo(500);
        assertThat(res.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(count("SELECT count(*) FROM transactions WHERE reference_id = 'r2'")).isZero();

        reset(accounts);
        assertThat(balanceOf(acc)).isEqualByComparingTo("100");
        assertThat(assertLedgerConsistent(acc)).isEqualByComparingTo("100");
        // The failed attempt left nothing behind, so the same reference can be retried.
        assertThat(postTxn(acc, "credit", 50, "r2").status()).isEqualTo(201);
    }

    /** The debit leg and its balance update succeed, then the credit side blows up: nothing may survive. */
    @Test
    void failureBetweenLegsRollsBackWholeTransfer() {
        String a = createAccount("A");
        String b = createAccount("B");
        postTxn(a, "credit", 100, "seed");
        postTxn(b, "credit", 50, "seed");
        clearInvocations(accounts); // count only the transfer's calls below, not the two seeds above

        // 1st call (debit of A) runs for real; 2nd call (credit of B) fails.
        doCallRealMethod()
                .doThrow(new RuntimeException("simulated crash between transfer legs"))
                .when(accounts).applyBalanceChange(any(), any());
        Res res = post("/transfers", body("from_account_id", a, "to_account_id", b, "amount", 30, "reference_id", "t1"));
        assertThat(res.status()).isEqualTo(500);

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<BigDecimal> deltas = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accounts, times(2)).applyBalanceChange(ids.capture(), deltas.capture());
        assertThat(ids.getAllValues()).containsExactly(UUID.fromString(a), UUID.fromString(b));
        assertThat(deltas.getAllValues().get(0)).isEqualByComparingTo("-30");

        reset(accounts);
        assertThat(balanceOf(a)).isEqualByComparingTo("100");
        assertThat(balanceOf(b)).isEqualByComparingTo("50");
        assertThat(count("SELECT count(*) FROM transactions WHERE transfer_id IS NOT NULL")).isZero();
        assertLedgerConsistent(a);
        assertLedgerConsistent(b);
    }
}
