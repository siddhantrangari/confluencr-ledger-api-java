package com.confluencr.ledger.service;

import com.confluencr.ledger.error.AccountNotFoundException;
import com.confluencr.ledger.model.Account;
import com.confluencr.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account create(String name) {
        return accounts.insert(name);
    }

    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
