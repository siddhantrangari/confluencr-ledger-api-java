package com.confluencr.ledger.repository;

import com.confluencr.ledger.model.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the transactions table. There is deliberately no update or delete method. */
@Repository
public class TransactionRepository {

    private static final String COLUMNS =
            "id, account_id, type, amount, description, reference_id, transfer_id, created_at";

    private static final RowMapper<Transaction> MAPPER = (rs, rowNum) -> new Transaction(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("type"),
            rs.getBigDecimal("amount"),
            rs.getString("description"),
            rs.getString("reference_id"),
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transaction> findByReference(UUID accountId, String referenceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE account_id = ? AND reference_id = ?",
                MAPPER, accountId, referenceId).stream().findFirst();
    }

    /** id and created_at come from column defaults. transferId is null except for transfer legs. */
    public Transaction insert(UUID accountId, String type, BigDecimal amount, String referenceId,
                              String description, UUID transferId) {
        return jdbc.queryForObject(
                "INSERT INTO transactions (account_id, type, amount, reference_id, description, transfer_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                MAPPER, accountId, type, amount, referenceId, description, transferId);
    }

    public long count(UUID accountId, String type) {
        List<Object> params = new ArrayList<>();
        String where = where(accountId, type, params);
        return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE " + where, Long.class, params.toArray());
    }

    /** Latest first. seq breaks ties when two rows share created_at, so paging is deterministic. */
    public List<Transaction> findPage(UUID accountId, String type, int limit, long offset) {
        List<Object> params = new ArrayList<>();
        String where = where(accountId, type, params);
        params.add(limit);
        params.add(offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM transactions WHERE " + where
                + " ORDER BY created_at DESC, seq DESC LIMIT ? OFFSET ?", MAPPER, params.toArray());
    }

    /** Builds the WHERE clause for the history queries. Values always go in as ? parameters, never into the SQL text. */
    private static String where(UUID accountId, String type, List<Object> params) {
        params.add(accountId);
        if (type == null) {
            return "account_id = ?";
        }
        params.add(type);
        return "account_id = ? AND type = ?";
    }
}
