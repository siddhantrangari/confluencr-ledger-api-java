package com.confluencr.ledger.error;

import org.springframework.http.HttpStatus;

public class BalanceLimitExceededException extends ApiException {

    public BalanceLimitExceededException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_LIMIT_EXCEEDED",
                "This transaction would exceed the maximum balance an account can hold", null);
    }
}
