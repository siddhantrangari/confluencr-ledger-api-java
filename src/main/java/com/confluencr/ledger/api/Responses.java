package com.confluencr.ledger.api;

import com.confluencr.ledger.model.Balance;
import com.confluencr.ledger.model.Transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Response bodies that wrap the model records. */
public final class Responses {

    private Responses() {
    }

    public record TransactionCreated(Transaction transaction, Balance account) {
    }

    public record Pagination(int page, int limit, long totalRecords, long totalPages) {
    }

    public record TransactionPage(List<Transaction> data, Pagination pagination) {
    }

    public record TransferCreated(
            UUID transferId,
            String referenceId,
            BigDecimal amount,
            Transaction debit,
            Transaction credit,
            Balance fromAccount,
            Balance toAccount) {
    }

    public record Health(String status) {
    }
}
