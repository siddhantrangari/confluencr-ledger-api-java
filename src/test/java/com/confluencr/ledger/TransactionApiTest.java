package com.confluencr.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionApiTest extends IntegrationTest {

    @Test
    void creditIncreasesBalance() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "r1");
        Res res = postTxn(acc, "credit", 50, "r2", "invoice 42");
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("account").toString())
                .isEqualTo("{\"account_id\":\"" + acc + "\",\"balance\":\"150.00\"}");
        var txn = res.body().get("transaction");
        assertThat(txn.get("type").asText()).isEqualTo("credit");
        assertThat(txn.get("amount").asText()).isEqualTo("50.00");
        assertThat(txn.get("description").asText()).isEqualTo("invoice 42");
        assertThat(txn.get("reference_id").asText()).isEqualTo("r2");
        assertThat(txn.get("account_id").asText()).isEqualTo(acc);
        assertThat(txn.get("transfer_id").isNull()).isTrue();
        UUID.fromString(txn.get("id").asText());
        assertThat(balanceOf(acc)).isEqualByComparingTo("150");
    }

    @Test
    void debitDecreasesBalance() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "r1");
        Res res = postTxn(acc, "debit", 30, "r2");
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("account").get("balance").asText()).isEqualTo("70.00");
    }

    @Test
    void debitWithInsufficientBalanceIsRejected() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "r1");
        Res res = postTxn(acc, "debit", 150, "r2");
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.errorCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(res.error().get("details").get("balance").asText()).isEqualTo("100.00");
        assertThat(res.error().get("details").get("requested_amount").asText()).isEqualTo("150.00");
        assertThat(balanceOf(acc)).isEqualByComparingTo("100");
        // The rejected debit left no row behind.
        assertThat(count("SELECT count(*) FROM transactions WHERE account_id = ?", UUID.fromString(acc))).isEqualTo(1);
    }

    @Test
    void debitOfExactBalanceIsAllowed() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "r1");
        assertThat(postTxn(acc, "debit", 100, "r2").status()).isEqualTo(201);
        assertThat(balanceOf(acc)).isEqualByComparingTo("0");
    }

    @Test
    void debitOnNewAccountIsRejected() {
        assertThat(postTxn(createAccount(), "debit", "0.01", "r1").status()).isEqualTo(422);
    }

    /** The brief's example: 0 +100 +50 -30 -20 = 100, plus a rejected debit that must not count. */
    @Test
    void balanceEqualsSumOfValidTransactions() {
        String acc = createAccount();
        assertThat(postTxn(acc, "credit", 100, "a").status()).isEqualTo(201);
        assertThat(postTxn(acc, "credit", 50, "b").status()).isEqualTo(201);
        assertThat(postTxn(acc, "debit", 30, "c").status()).isEqualTo(201);
        assertThat(postTxn(acc, "debit", 20, "d").status()).isEqualTo(201);
        assertThat(postTxn(acc, "debit", 1000, "e").status()).isEqualTo(422);
        assertThat(balanceOf(acc)).isEqualByComparingTo("100");
        assertThat(assertLedgerConsistent(acc)).isEqualByComparingTo("100");
    }

    @Test
    void duplicateReferenceCreatesNothingAndReturnsExisting() {
        String acc = createAccount();
        var first = postTxn(acc, "credit", 100, "dup-1").body().get("transaction");

        Res res = postTxn(acc, "credit", 100, "dup-1");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.errorCode()).isEqualTo("DUPLICATE_TRANSACTION");
        assertThat(res.error().get("details").get("existing_transaction")).isEqualTo(first);
        assertThat(balanceOf(acc)).isEqualByComparingTo("100");
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(1);
    }

    @Test
    void duplicateWithDifferentPayloadIsStillRejected() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "dup-1");
        Res res = postTxn(acc, "debit", 5, "dup-1");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.error().get("details").get("existing_transaction").get("amount").asText()).isEqualTo("100.00");
        assertThat(balanceOf(acc)).isEqualByComparingTo("100");
    }

    /** A retried debit that already succeeded must say "already exists", not "insufficient balance". */
    @Test
    void duplicateIsReportedBeforeInsufficientBalance() {
        String acc = createAccount();
        postTxn(acc, "credit", 100, "r1");
        postTxn(acc, "debit", 80, "pay-1");               // balance now 20
        Res res = postTxn(acc, "debit", 80, "pay-1");     // retry of the same payment
        assertThat(res.status()).isEqualTo(409);
        assertThat(balanceOf(acc)).isEqualByComparingTo("20");
    }

    @Test
    void sameReferenceAllowedOnDifferentAccounts() {
        String a = createAccount("A");
        String b = createAccount("B");
        assertThat(postTxn(a, "credit", 10, "shared-ref").status()).isEqualTo(201);
        assertThat(postTxn(b, "credit", 20, "shared-ref").status()).isEqualTo(201);
        assertThat(balanceOf(a)).isEqualByComparingTo("10");
        assertThat(balanceOf(b)).isEqualByComparingTo("20");
    }

    /** A rejected debit stores nothing, so the client may retry the same reference later. */
    @Test
    void rejectedReferenceCanBeReused() {
        String acc = createAccount();
        assertThat(postTxn(acc, "debit", 50, "pay-1").status()).isEqualTo(422);
        postTxn(acc, "credit", 100, "topup");
        assertThat(postTxn(acc, "debit", 50, "pay-1").status()).isEqualTo(201);
    }

    @Test
    void referenceIdIsCaseSensitive() {
        String acc = createAccount();
        assertThat(postTxn(acc, "credit", 1, "ABC").status()).isEqualTo(201);
        assertThat(postTxn(acc, "credit", 1, "abc").status()).isEqualTo(201);
    }

    @Test
    void transactionOnUnknownAccountIs404() {
        Res res = postTxn(UUID.randomUUID().toString(), "credit", 10, "r1");
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void transactionOnMalformedAccountIdIs400() {
        assertThat(postTxn("abc", "credit", 10, "r1").status()).isEqualTo(400);
    }

    static Stream<Object> invalidTypes() {
        return Stream.of("refund", "CREDIT", "", null, 1);
    }

    @ParameterizedTest
    @MethodSource("invalidTypes")
    void invalidType(Object badType) {
        String acc = createAccount();
        Res res = postTxn(acc, badType, 10, "r1");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_TRANSACTION_TYPE");
    }

    static Stream<Object> invalidAmounts() {
        return Stream.of(0, -5, "0.00", "-1", "10.001", 0.001, "abc", null, true, "NaN", "Infinity",
                new BigDecimal("1000000000.01"));
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void invalidAmount(Object badAmount) {
        String acc = createAccount();
        Res res = postTxn(acc, "credit", badAmount, "r1");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_AMOUNT");
        assertThat(balanceOf(acc)).isEqualByComparingTo("0");
    }

    static Stream<Arguments> validAmounts() {
        return Stream.of(
                Arguments.of(10, "10.00"),
                Arguments.of("10.5", "10.50"),
                Arguments.of(0.1, "0.10"),
                Arguments.of("1000000000.00", "1000000000.00"));
    }

    @ParameterizedTest
    @MethodSource("validAmounts")
    void validAmountFormats(Object amount, String stored) {
        Res res = postTxn(createAccount(), "credit", amount, "r1");
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("transaction").get("amount").asText()).isEqualTo(stored);
    }

    /** 0.1 + 0.2 is 0.30000000000000004 in floating point; here it must be exactly 0.30. */
    @Test
    void decimalAmountsHaveNoFloatDrift() {
        String acc = createAccount();
        postTxn(acc, "credit", 0.1, "a");
        postTxn(acc, "credit", 0.2, "b");
        assertThat(get("/accounts/" + acc + "/balance").body().get("balance").asText()).isEqualTo("0.30");
    }

    static Stream<Object> invalidReferences() {
        return Stream.of("", "   ", null, "x".repeat(65), 123);
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void invalidReferenceId(Object badRef) {
        Res res = postTxn(createAccount(), "credit", 10, badRef);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void missingReferenceId() {
        String acc = createAccount();
        Res res = post("/accounts/" + acc + "/transactions", body("type", "credit", "amount", 10));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.error().get("details").toString())
                .isEqualTo("[{\"field\":\"reference_id\",\"message\":\"is required\"}]");
    }

    @Test
    void mixedValidationErrorsUseGenericCode() {
        String acc = createAccount();
        Res res = post("/accounts/" + acc + "/transactions", body("type", "refund", "amount", -1));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.error().get("details")).hasSize(3); // type, amount, reference_id
    }

    @Test
    void unknownFieldIsRejected() {
        Res res = post("/accounts/" + createAccount() + "/transactions",
                body("type", "credit", "amount", 10, "reference_id", "r1", "currency", "INR"));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.error().get("details").get(0).get("field").asText()).isEqualTo("currency");
    }

    @Test
    void descriptionIsOptionalAndLimited() {
        String acc = createAccount();
        assertThat(postTxn(acc, "credit", 1, "a").body().get("transaction").get("description").isNull()).isTrue();
        assertThat(postTxn(acc, "credit", 1, "b", "x".repeat(256)).status()).isEqualTo(400);
    }

    @Test
    void balanceLimitIsEnforced() {
        String acc = createAccount();
        // Put the account just under the NUMERIC(18,2) ceiling directly, then try to credit past it.
        jdbc.update("UPDATE accounts SET balance = 9999999999999999.00 WHERE id = ?", UUID.fromString(acc));
        Res res = postTxn(acc, "credit", 1, "r1");
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.errorCode()).isEqualTo("BALANCE_LIMIT_EXCEEDED");
        assertThat(count("SELECT count(*) FROM transactions")).isZero();
    }

    @Test
    void nonJsonContentTypeIsRejected() {
        Res res = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/accounts/" + createAccount() + "/transactions")
                .contentType("text/plain").content("hello"));
        assertThat(res.status()).isEqualTo(415);
        assertThat(res.errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
}
