package com.confluencr.ledger.api;

import com.confluencr.ledger.validation.ValidAmount;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateTransactionRequest(
        @NotNull(message = "is required")
        @Pattern(regexp = "credit|debit", message = "must be 'credit' or 'debit'")
        String type,

        @NotNull(message = "is required")
        @ValidAmount
        BigDecimal amount,

        // An idempotency key: compared exactly (case-sensitive, not trimmed), but must not be blank.
        @NotNull(message = "is required")
        @Size(min = 1, max = 64)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String referenceId,

        @Size(max = 255)
        String description) {
}
