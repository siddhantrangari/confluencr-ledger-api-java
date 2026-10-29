package com.confluencr.ledger.error;

import org.springframework.http.HttpStatus;

public class SameAccountTransferException extends ApiException {

    public SameAccountTransferException() {
        super(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER",
                "from_account_id and to_account_id must be different accounts", null);
    }
}
