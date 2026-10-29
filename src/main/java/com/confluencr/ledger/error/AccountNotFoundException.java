package com.confluencr.ledger.error;

import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

public class AccountNotFoundException extends ApiException {

    public AccountNotFoundException(UUID accountId) {
        super(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account " + accountId + " does not exist",
                Map.of("account_id", accountId.toString()));
    }
}
