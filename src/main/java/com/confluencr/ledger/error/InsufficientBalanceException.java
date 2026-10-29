package com.confluencr.ledger.error;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public class InsufficientBalanceException extends ApiException {

    public InsufficientBalanceException(UUID accountId, BigDecimal balance, BigDecimal requested) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                "Insufficient balance: available " + balance.toPlainString()
                        + ", requested " + requested.toPlainString(),
                Map.of("account_id", accountId.toString(),
                        "balance", balance.toPlainString(),
                        "requested_amount", requested.toPlainString()));
    }
}
