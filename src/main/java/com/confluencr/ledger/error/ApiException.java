package com.confluencr.ledger.error;

import org.springframework.http.HttpStatus;

/**
 * A domain error that maps to one HTTP status and one stable machine-readable code.
 * Unchecked, so throwing it inside a @Transactional method rolls the transaction back.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    protected ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
