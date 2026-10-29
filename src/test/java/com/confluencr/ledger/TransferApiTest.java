package com.confluencr.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiTest extends IntegrationTest {

    private String a;
    private String b;

    /** The brief's example: A has 100, B has 50. */
    @BeforeEach
    void accountsAAndB() {
        a = createAccount("A");
        b = createAccount("B");
        postTxn(a, "credit", 100, "seed");
        postTxn(b, "credit", 50, "seed");
    }

    private Res transfer(String from, String to, Object amount, String ref) {
        return post("/transfers", body("from_account_id", from, "to_account_id", to, "amount", amount, "reference_id", ref));
    }

    @Test
    void transferMovesMoney() {
        Res res = transfer(a, b, 30, "t1");
        assertThat(res.status()).isEqualTo(201);
        var body = res.body();
        assertThat(body.get("from_account").toString()).isEqualTo("{\"account_id\":\"" + a + "\",\"balance\":\"70.00\"}");
        assertThat(body.get("to_account").toString()).isEqualTo("{\"account_id\":\"" + b + "\",\"balance\":\"80.00\"}");
        assertThat(body.get("amount").asText()).isEqualTo("30.00");
        assertThat(body.get("debit").get("type").asText()).isEqualTo("debit");
        assertThat(body.get("debit").get("account_id").asText()).isEqualTo(a);
        assertThat(body.get("credit").get("type").asText()).isEqualTo("credit");
        assertThat(body.get("credit").get("account_id").asText()).isEqualTo(b);
        // Both legs are linked by the same transfer_id and carry the client's reference_id.
        String transferId = body.get("transfer_id").asText();
        assertThat(body.get("debit").get("transfer_id").asText()).isEqualTo(transferId);
        assertThat(body.get("credit").get("transfer_id").asText()).isEqualTo(transferId);
        assertThat(body.get("debit").get("reference_id").asText()).isEqualTo("t1");
        assertThat(body.get("credit").get("reference_id").asText()).isEqualTo("t1");
        assertThat(body.get("debit").get("description").asText()).isEqualTo("Transfer to " + b);
        assertThat(body.get("credit").get("description").asText()).isEqualTo("Transfer from " + a);
        assertThat(balanceOf(a)).isEqualByComparingTo("70");
        assertThat(balanceOf(b)).isEqualByComparingTo("80");
        assertLedgerConsistent(a);
        assertLedgerConsistent(b);
    }

    @Test
    void transferLegsAppearInHistory() {
        post("/transfers", body("from_account_id", a, "to_account_id", b, "amount", 30, "reference_id", "t1",
                "description", "rent share"));
        var latestA = get("/accounts/" + a + "/transactions").body().get("data").get(0);
        var latestB = get("/accounts/" + b + "/transactions").body().get("data").get(0);
        assertThat(latestA.get("type").asText()).isEqualTo("debit");
        assertThat(latestA.get("description").asText()).isEqualTo("rent share");
        assertThat(latestB.get("type").asText()).isEqualTo("credit");
        assertThat(latestB.get("description").asText()).isEqualTo("rent share");
    }

    @Test
    void transferWithInsufficientBalanceChangesNothing() {
        Res res = transfer(a, b, 150, "t1");
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.errorCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(balanceOf(a)).isEqualByComparingTo("100");
        assertThat(balanceOf(b)).isEqualByComparingTo("50");
        assertThat(count("SELECT count(*) FROM transactions WHERE transfer_id IS NOT NULL")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void transferRequiresBothAccountsToExist(boolean sourceMissing) {
        String ghost = UUID.randomUUID().toString();
        Res res = sourceMissing ? transfer(ghost, a, 10, "t1") : transfer(a, ghost, 10, "t1");
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.error().get("details").get("account_id").asText()).isEqualTo(ghost);
        assertThat(balanceOf(a)).isEqualByComparingTo("100");
    }

    @Test
    void selfTransferIsRejected() {
        Res res = transfer(a, a, 10, "t1");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("SAME_ACCOUNT_TRANSFER");
    }

    @Test
    void duplicateTransferIsRejected() {
        assertThat(transfer(a, b, 30, "t-dup").status()).isEqualTo(201);
        Res res = transfer(a, b, 30, "t-dup");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.errorCode()).isEqualTo("DUPLICATE_TRANSACTION");
        assertThat(balanceOf(a)).isEqualByComparingTo("70");
        assertThat(balanceOf(b)).isEqualByComparingTo("80");
    }

    @Test
    void transferReferenceAlreadyUsedOnDestination() {
        postTxn(b, "credit", 1, "taken");
        Res res = transfer(a, b, 30, "taken");
        assertThat(res.status()).isEqualTo(409);
        assertThat(balanceOf(a)).isEqualByComparingTo("100");
        assertThat(balanceOf(b)).isEqualByComparingTo("51");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount=0", "amount=1.234", "from_account_id=nope", "reference_id="})
    void transferValidation(String patch) {
        String[] kv = patch.split("=", 2);
        Map<String, Object> request = body("from_account_id", a, "to_account_id", b, "amount", 10, "reference_id", "t1");
        request.put(kv[0], kv[1]);
        assertThat(post("/transfers", request).status()).isEqualTo(400);
    }
}
