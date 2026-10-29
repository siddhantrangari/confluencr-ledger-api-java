package com.confluencr.ledger.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Only the name. id, balance and created_at are set by the system; sending them is a 400. */
public record CreateAccountRequest(
        @NotNull(message = "is required") @Size(min = 1, max = 100) String name) {

    public CreateAccountRequest {
        // Trim before validation runs, so "   " fails @Size(min = 1) and "  Bob  " is stored as "Bob".
        if (name != null) {
            name = name.strip();
        }
    }
}
