package com.confluencr.ledger.api;

import com.confluencr.ledger.api.Responses.TransactionCreated;
import com.confluencr.ledger.api.Responses.TransactionPage;
import com.confluencr.ledger.config.ApiErrors;
import com.confluencr.ledger.service.TransactionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Deliberately absent: any PUT, PATCH or DELETE for transactions. They are immutable,
 * and the database enforces that too (see the trigger in V1__init.sql).
 */
@RestController
@Tag(name = "transactions")
public class TransactionController {

    static final int MAX_PAGE = 1_000_000;
    static final int MAX_PAGE_SIZE = 100;

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/accounts/{account_id}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiErrors({400, 404, 409, 422})
    public TransactionCreated create(@PathVariable("account_id") UUID accountId,
                                     @Valid @RequestBody CreateTransactionRequest request) {
        return transactionService.create(accountId, request);
    }

    @GetMapping("/accounts/{account_id}/transactions")
    @ApiErrors({400, 404})
    public TransactionPage history(
            @PathVariable("account_id") UUID accountId,
            @RequestParam(required = false) @Pattern(regexp = "credit|debit", message = "must be 'credit' or 'debit'") String type,
            @RequestParam(defaultValue = "1") @Min(1) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int limit) {
        return transactionService.history(accountId, type, page, limit);
    }
}
