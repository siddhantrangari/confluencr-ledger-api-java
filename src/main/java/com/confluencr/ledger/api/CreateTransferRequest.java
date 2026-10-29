package com.confluencr.ledger.api;

import com.confluencr.ledger.validation.ValidAmount;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(
        @NotNull(message = "is required") UUID fromAccountId,
        @NotNull(message = "is required") UUID toAccountId,
        @NotNull(message = "is required") @ValidAmount BigDecimal amount,
        @NotNull(message = "is required")
        @Size(min = 1, max = 64)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String referenceId,
        @Size(max = 255) String description) {
}
