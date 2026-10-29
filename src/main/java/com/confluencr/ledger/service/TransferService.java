package com.confluencr.ledger.service;

import com.confluencr.ledger.api.CreateTransferRequest;
import com.confluencr.ledger.api.Responses.TransferCreated;
import com.confluencr.ledger.error.AccountNotFoundException;
import com.confluencr.ledger.error.DuplicateTransactionException;
import com.confluencr.ledger.error.InsufficientBalanceException;
import com.confluencr.ledger.error.SameAccountTransferException;
import com.confluencr.ledger.model.Account;
import com.confluencr.ledger.model.Balance;
import com.confluencr.ledger.model.Transaction;
import com.confluencr.ledger.repository.AccountRepository;
import com.confluencr.ledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public TransferService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * Debits the source and credits the destination in ONE database transaction:
     * either both legs and both balance updates commit, or none of them do.
     */
    @Transactional
    public TransferCreated transfer(CreateTransferRequest request) {
        UUID from = request.fromAccountId();
        UUID to = request.toAccountId();
        if (from.equals(to)) {
            throw new SameAccountTransferException();
        }
        BigDecimal amount = Money.normalize(request.amount());

        // Deadlock avoidance: always lock the two accounts in the same (sorted) order. If A->B and B->A
        // run at once, both lock the smaller id first, so one simply waits for the other instead of each
        // holding one lock and waiting for the other's. String order of a UUID is the order Postgres sorts it in.
        List<UUID> lockOrder = List.of(from, to).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        Map<UUID, Account> locked = new HashMap<>();
        for (UUID id : lockOrder) {
            locked.put(id, accounts.lockById(id).orElseThrow(() -> new AccountNotFoundException(id)));
        }
        Account source = locked.get(from);

        // Both legs store the client's reference_id, so it must be unused on both accounts.
        for (UUID id : List.of(from, to)) {
            transactions.findByReference(id, request.referenceId()).ifPresent(existing -> {
                throw new DuplicateTransactionException(existing);
            });
        }

        if (source.balance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(from, source.balance(), amount);
        }

        UUID transferId = UUID.randomUUID();
        String debitDescription = request.description() != null ? request.description() : "Transfer to " + to;
        String creditDescription = request.description() != null ? request.description() : "Transfer from " + from;

        Transaction debit = transactions.insert(
                from, Transaction.DEBIT, amount, request.referenceId(), debitDescription, transferId);
        BigDecimal fromBalance = accounts.applyBalanceChange(from, amount.negate());
        Transaction credit = transactions.insert(
                to, Transaction.CREDIT, amount, request.referenceId(), creditDescription, transferId);
        BigDecimal toBalance = accounts.applyBalanceChange(to, amount);

        return new TransferCreated(transferId, request.referenceId(), amount, debit, credit,
                new Balance(from, fromBalance), new Balance(to, toBalance));
    }
}
