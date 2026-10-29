package com.confluencr.ledger.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A row of the transactions table (without the internal seq column). Also the API response
 * for a transaction. transferId is set only on the two legs of a transfer.
 */
public record Transaction(
        UUID id,
        UUID accountId,
        String type,
        BigDecimal amount,
        String description,
        String referenceId,
        UUID transferId,
        OffsetDateTime createdAt) {

    public static final String CREDIT = "credit";
    public static final String DEBIT = "debit";
}
