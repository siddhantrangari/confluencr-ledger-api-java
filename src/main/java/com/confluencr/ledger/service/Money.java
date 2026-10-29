package com.confluencr.ledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Money {

    private Money() {
    }

    /**
     * 10 -> 10.00, 10.5 -> 10.50, so amounts print the same everywhere. Validation already
     * guarantees at most 2 decimal places, so UNNECESSARY can never round (it would throw if it had to).
     */
    static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
