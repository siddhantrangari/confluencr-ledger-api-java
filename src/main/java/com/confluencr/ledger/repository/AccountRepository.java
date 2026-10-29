package com.confluencr.ledger.repository;

import com.confluencr.ledger.error.BalanceLimitExceededException;
import com.confluencr.ledger.model.Account;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** SQL for the accounts table. Plain JdbcTemplate: every statement that runs is visible here. */
@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, name, balance, created_at";

    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getBigDecimal("balance"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** id, balance (0) and created_at all come from column defaults; the client only supplies the name. */
    public Account insert(String name) {
        return jdbc.queryForObject("INSERT INTO accounts (name) VALUES (?) RETURNING " + COLUMNS, MAPPER, name);
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * SELECT ... FOR UPDATE: takes a row lock on the account until the surrounding transaction ends.
     * Any other transaction that wants to change this account blocks here until we COMMIT or ROLLBACK,
     * so balance checks and updates on one account happen one at a time.
     * Must be called inside a @Transactional method, otherwise the lock is released immediately.
     */
    public Optional<Account> lockById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * Relative update (balance = balance + delta) computed by Postgres on the locked row, never
     * "read the balance into Java, add, write it back", which could lose a concurrent update.
     * Returns the new balance.
     */
    public BigDecimal applyBalanceChange(UUID id, BigDecimal delta) {
        try {
            return jdbc.queryForObject(
                    "UPDATE accounts SET balance = balance + ? WHERE id = ? RETURNING balance",
                    BigDecimal.class, delta, id);
        } catch (DataIntegrityViolationException ex) {
            // SQLSTATE 22003 numeric_value_out_of_range: the new balance doesn't fit NUMERIC(18,2).
            if (ex.getMostSpecificCause() instanceof SQLException sql && "22003".equals(sql.getSQLState())) {
                throw new BalanceLimitExceededException();
            }
            throw ex;
        }
    }
}
