package com.confluencr.ledger.error;

import com.confluencr.ledger.web.RequestIdFilter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every error, ours or the framework's, leaves through {@link #error} so clients always see
 * {"error": {"code", "message", "details", "request_id"}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Validation failures on these fields get a specific code instead of the generic VALIDATION_ERROR. */
    private static final Map<String, String> FIELD_ERROR_CODES = Map.of(
            "type", "INVALID_TRANSACTION_TYPE",
            "amount", "INVALID_AMOUNT",
            "page", "INVALID_PAGINATION",
            "limit", "INVALID_PAGINATION");

    private static final List<FieldProblem> BODY_PROBLEM =
            List.of(new FieldProblem("body", "Request body must be a valid JSON object"));

    /** One entry of "details" for a validation error. */
    public record FieldProblem(String field, String message) {
    }

    // ---------- our domain errors ----------

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        return error(request, ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    // ---------- validation (all 400) ----------

    /** @Valid on a request body failed: e.g. amount <= 0, missing reference_id. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldProblem> problems = ex.getFieldErrors().stream()
                .map(e -> new FieldProblem(snakeCase(e.getField()), e.getDefaultMessage()))
                .toList();
        return validationError(request, problems);
    }

    /** A constraint on a @RequestParam failed: e.g. page=0, limit=101, type=refund. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldProblem> problems = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(e -> new FieldProblem(
                        result.getMethodParameter().getParameterName(), messageOf(e))))
                .toList();
        return validationError(request, problems);
    }

    /** A path or query value has the wrong type: e.g. account_id=abc, page=1.5. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return validationError(request, List.of(new FieldProblem(ex.getName(), "Invalid value '" + ex.getValue() + "'")));
    }

    /**
     * Jackson could not turn the body into the request record. If it failed on a specific field
     * (unknown field, "abc" as an amount, 123 as a name) report that field; otherwise the body as a whole
     * is broken (not JSON, not an object, missing).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (ex.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String field = jme.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : String.valueOf(ref.getIndex()))
                    .collect(Collectors.joining("."));
            String message = jme instanceof UnrecognizedPropertyException ? "Unknown field" : "Invalid value";
            return validationError(request, List.of(new FieldProblem(field, message)));
        }
        return error(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Invalid request: body: Request body must be a valid JSON object", BODY_PROBLEM);
    }

    // ---------- routing ----------

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoRoute(NoResourceFoundException ex, HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not Found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return error(request, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method Not Allowed", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return error(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json", null);
    }

    // ---------- database backstops ----------
    // The service layer prevents these, but if a future bug slips past it, the database
    // constraint still says no and the client still gets the right answer.

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> handleUniqueViolation(DuplicateKeyException ex, HttpServletRequest request) {
        log.warn("unique constraint backstop hit: {}", ex.getMostSpecificCause().getMessage());
        return error(request, HttpStatus.CONFLICT, "DUPLICATE_TRANSACTION", "Transaction already exists", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraint(DataIntegrityViolationException ex, HttpServletRequest request) {
        String cause = String.valueOf(ex.getMostSpecificCause().getMessage());
        log.warn("constraint backstop hit: {}", cause);
        if (cause.contains("accounts_balance_non_negative")) {
            return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE", "Insufficient balance", null);
        }
        if (ex.getMostSpecificCause() instanceof SQLException sql && "22003".equals(sql.getSQLState())) {
            return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_LIMIT_EXCEEDED",
                    "This transaction would exceed the maximum balance an account can hold", null);
        }
        return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request violates a data constraint", null);
    }

    // ---------- anything else ----------

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled error", ex);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred; nothing was changed", null);
    }

    // ---------- helpers ----------

    /** A specific code only when every problem is of that one kind; mixed problems get VALIDATION_ERROR. */
    private ResponseEntity<ErrorResponse> validationError(HttpServletRequest request, List<FieldProblem> problems) {
        List<FieldProblem> sorted = problems.stream()
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::message))
                .toList();
        List<String> codes = sorted.stream()
                .map(p -> FIELD_ERROR_CODES.getOrDefault(p.field(), "VALIDATION_ERROR"))
                .distinct()
                .toList();
        String code = codes.size() == 1 ? codes.get(0) : "VALIDATION_ERROR";
        FieldProblem first = sorted.get(0);
        return error(request, HttpStatus.BAD_REQUEST, code,
                "Invalid request: " + first.field() + ": " + first.message(), sorted);
    }

    private ResponseEntity<ErrorResponse> error(HttpServletRequest request, HttpStatus status, String code,
                                                String message, Object details) {
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(new ErrorResponse.ErrorBody(code, message, details, requestId)));
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value";
    }

    /** referenceId -> reference_id, to match the JSON field names the client sent. */
    private static String snakeCase(String javaName) {
        return javaName.replaceAll("([A-Z])", "_$1").toLowerCase();
    }
}
