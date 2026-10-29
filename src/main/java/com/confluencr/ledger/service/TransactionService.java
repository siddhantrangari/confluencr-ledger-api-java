package com.confluencr.ledger.service;

import com.confluencr.ledger.api.CreateTransactionRequest;
import com.confluencr.ledger.api.Responses.Pagination;
import com.confluencr.ledger.api.Responses.TransactionCreated;
import com.confluencr.ledger.api.Responses.TransactionPage;
import com.confluencr.ledger.error.AccountNotFoundException;
import com.confluencr.ledger.error.DuplicateTransactionException;
import com.confluencr.ledger.error.InsufficientBalanceException;
import com.confluencr.ledger.model.Account;
import com.confluencr.ledger.model.Balance;
import com.confluencr.ledger.model.Transaction;
import com.confluencr.ledger.repository.AccountRepository;
import com.confluencr.ledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public TransactionService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * Inserts one credit/debit and updates the balance as a single database transaction.
     *
     * The order of the steps matters:
     * 1. Lock the account row: concurrent requests for this account now queue up behind us.
     * 2. Duplicate check BEFORE the balance check: retrying a debit that already succeeded must
     *    answer "already exists", even if the balance has since dropped below the amount.
     * 3. Balance check for debits (the row is locked, so the balance can't change under us).
     * 4. Insert the transaction, then update the balance.
     * Any exception rolls everything back, so nothing is ever half-written.
     */
    @Transactional
    public TransactionCreated create(UUID accountId, CreateTransactionRequest request) {
        BigDecimal amount = Money.normalize(request.amount());

        Account account = accounts.lockById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));

        transactions.findByReference(accountId, request.referenceId()).ifPresent(existing -> {
            throw new DuplicateTransactionException(existing);
        });

        boolean debit = Transaction.DEBIT.equals(request.type());
        if (debit && account.balance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(accountId, account.balance(), amount);
        }

        Transaction txn = transactions.insert(
                accountId, request.type(), amount, request.referenceId(), request.description(), null);
        BigDecimal balance = accounts.applyBalanceChange(accountId, debit ? amount.negate() : amount);
        return new TransactionCreated(txn, new Balance(accountId, balance));
    }

    /**
     * REPEATABLE READ: the existence check, the COUNT and the page query all read the same snapshot,
     * so total_records can't disagree with the rows returned if a write lands mid-request.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransactionPage history(UUID accountId, String type, int page, int limit) {
        accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        long total = transactions.count(accountId, type);
        List<Transaction> rows = transactions.findPage(accountId, type, limit, (long) (page - 1) * limit);
        long totalPages = (total + limit - 1) / limit; // ceiling division
        return new TransactionPage(rows, new Pagination(page, limit, total, totalPages));
    }
}
