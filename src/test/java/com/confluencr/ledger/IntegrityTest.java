package com.confluencr.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Immutability, and the database constraints that back up the application code (tested without the API). */
class IntegrityTest extends IntegrationTest {

    // ---------- immutability ----------

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void noApiRouteCanModifyTransactions(String method) {
        String acc = createAccount();
        String txnId = postTxn(acc, "credit", 10, "r1").body().get("transaction").get("id").asText();
        for (String url : List.of("/accounts/" + acc + "/transactions",
                "/accounts/" + acc + "/transactions/" + txnId, "/transactions/" + txnId)) {
            int status = send(HttpMethod.valueOf(method), url, body("amount", 999)).status();
            assertThat(status).as(method + " " + url).isIn(404, 405);
        }
        assertThat(balanceOf(acc)).isEqualByComparingTo("10");
    }

    @Test
    void databaseRejectsUpdateOfATransaction() {
        postTxn(createAccount(), "credit", 10, "r1");
        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET amount = 999"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void databaseRejectsDeleteOfATransaction() {
        postTxn(createAccount(), "credit", 10, "r1");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transactions"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(1);
    }

    // ---------- database constraints (defence in depth) ----------

    @Test
    void dbRejectsNegativeBalance() {
        UUID acc = UUID.fromString(createAccount());
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", acc))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("accounts_balance_non_negative");
    }

    @Test
    void dbRejectsDuplicateReferencePerAccount() {
        UUID acc = UUID.fromString(createAccount());
        String insert = "INSERT INTO transactions (account_id, type, amount, reference_id) VALUES (?, 'credit', 1, 'r')";
        jdbc.update(insert, acc);
        assertThatThrownBy(() -> jdbc.update(insert, acc))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("transactions_account_reference_unique");
    }

    @ParameterizedTest
    @CsvSource({"refund, 1", "credit, 0", "debit, -1"})
    void dbRejectsInvalidTypeOrAmount(String type, BigDecimal amount) {
        UUID acc = UUID.fromString(createAccount());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transactions (account_id, type, amount, reference_id) VALUES (?, ?, ?, 'r')",
                acc, type, amount))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dbRejectsTransactionForMissingAccount() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO transactions (account_id, type, amount, reference_id) "
                + "VALUES (gen_random_uuid(), 'credit', 1, 'r')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("foreign key");
    }

    @Test
    void accountWithTransactionsCannotBeDeleted() {
        String acc = createAccount();
        postTxn(acc, "credit", 1, "r");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM accounts WHERE id = ?", UUID.fromString(acc)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("foreign key");
    }
}
