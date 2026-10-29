package com.confluencr.ledger.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A row of the accounts table. Also the API response for an account. */
public record Account(UUID id, String name, BigDecimal balance, OffsetDateTime createdAt) {
}
