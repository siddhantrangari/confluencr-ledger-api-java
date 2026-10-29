package com.confluencr.ledger.error;

/** The one error shape: {"error": {"code", "message", "details", "request_id"}}. */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, Object details, String requestId) {
    }
}
