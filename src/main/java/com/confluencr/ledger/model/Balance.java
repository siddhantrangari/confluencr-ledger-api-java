package com.confluencr.ledger.model;

import java.math.BigDecimal;
import java.util.UUID;

public record Balance(UUID accountId, BigDecimal balance) {
}
