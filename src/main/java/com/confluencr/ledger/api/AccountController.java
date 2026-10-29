package com.confluencr.ledger.api;

import com.confluencr.ledger.config.ApiErrors;
import com.confluencr.ledger.model.Account;
import com.confluencr.ledger.model.Balance;
import com.confluencr.ledger.service.AccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP layer only: validate input, call a service, return the result. No SQL here. */
@RestController
@Tag(name = "accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiErrors({400})
    public Account create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(request.name());
    }

    @GetMapping("/accounts/{account_id}")
    @ApiErrors({400, 404})
    public Account get(@PathVariable("account_id") UUID accountId) {
        return accountService.get(accountId);
    }

    @GetMapping("/accounts/{account_id}/balance")
    @ApiErrors({400, 404})
    public Balance balance(@PathVariable("account_id") UUID accountId) {
        Account account = accountService.get(accountId);
        return new Balance(account.id(), account.balance());
    }
}
