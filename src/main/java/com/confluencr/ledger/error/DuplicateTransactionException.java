package com.confluencr.ledger.error;

import com.confluencr.ledger.model.Transaction;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** The reference_id was already used on this account. The original transaction goes back to the client. */
public class DuplicateTransactionException extends ApiException {

    public DuplicateTransactionException(Transaction existing) {
        super(HttpStatus.CONFLICT, "DUPLICATE_TRANSACTION",
                "A transaction with reference_id '" + existing.referenceId() + "' already exists for account "
                        + existing.accountId() + "; no new transaction was created",
                Map.of("existing_transaction", existing));
    }
}
