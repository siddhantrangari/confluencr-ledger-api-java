package com.confluencr.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AccountApiTest extends IntegrationTest {

    @Test
    void createAccountReturnsSystemGeneratedFields() {
        Res res = post("/accounts", body("name", "Alice"));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "name", "balance", "created_at");
        UUID.fromString(res.body().get("id").asText());
        assertThat(res.body().get("name").asText()).isEqualTo("Alice");
        assertThat(res.body().get("balance").isTextual()).isTrue();
        assertThat(res.body().get("balance").asText()).isEqualTo("0.00");
        assertThat(res.body().get("created_at").asText()).endsWith("Z");
    }

    @Test
    void accountIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createAccount("user" + i));
        }
        assertThat(ids).hasSize(5);
    }

    @Test
    void nameIsTrimmed() {
        assertThat(post("/accounts", body("name", "  Bob  ")).body().get("name").asText()).isEqualTo("Bob");
    }

    @Test
    void clientCannotSetInitialBalance() {
        Res res = post("/accounts", body("name", "Alice", "balance", 500));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.error().get("details").get(0).get("field").asText()).isEqualTo("balance");
    }

    @Test
    void clientCannotSetIdOrCreatedAt() {
        assertThat(post("/accounts", body("name", "Alice", "id", UUID.randomUUID().toString())).status()).isEqualTo(400);
        assertThat(post("/accounts", body("name", "Alice", "created_at", "2020-01-01T00:00:00Z")).status()).isEqualTo(400);
    }

    static Stream<Map<String, Object>> invalidNames() {
        return Stream.of(body(), body("name", ""), body("name", "   "), body("name", null), body("name", 123));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void nameIsRequired(Map<String, Object> requestBody) {
        Res res = post("/accounts", requestBody);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void nameTooLong() {
        assertThat(post("/accounts", body("name", "x".repeat(101))).status()).isEqualTo(400);
        assertThat(post("/accounts", body("name", "x".repeat(100))).status()).isEqualTo(201);
    }

    @Test
    void malformedJsonBody() {
        Res res = post("/accounts", "{not json");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(res.error().get("details").toString())
                .isEqualTo("[{\"field\":\"body\",\"message\":\"Request body must be a valid JSON object\"}]");
    }

    @Test
    void bodyMustBeAnObject() {
        Res res = post("/accounts", "[\"Alice\"]");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_REQUEST_BODY");
    }

    @Test
    void missingBody() {
        Res res = post("/accounts", "");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_REQUEST_BODY");
    }

    @Test
    void getAccountAndBalance() {
        String id = createAccount();
        assertThat(get("/accounts/" + id).body().get("name").asText()).isEqualTo("Alice");
        Res res = get("/accounts/" + id + "/balance");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().toString()).isEqualTo("{\"account_id\":\"" + id + "\",\"balance\":\"0.00\"}");
    }

    @Test
    void balanceOfUnknownAccountIs404() {
        String missing = UUID.randomUUID().toString();
        Res res = get("/accounts/" + missing + "/balance");
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(res.error().get("details").get("account_id").asText()).isEqualTo(missing);
    }

    @Test
    void malformedAccountIdIs400() {
        Res res = get("/accounts/not-a-uuid/balance");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.error().get("details").get(0).get("field").asText()).isEqualTo("account_id");
    }

    @Test
    void errorShapeIsConsistentAndHasRequestId() {
        Res res = call(MockMvcRequestBuilders.get("/accounts/" + UUID.randomUUID()).header("X-Request-ID", "trace-123"));
        assertThat(res.error().properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "details", "request_id");
        assertThat(res.error().get("request_id").asText()).isEqualTo("trace-123");
        assertThat(res.raw().getHeader("X-Request-ID")).isEqualTo("trace-123");
    }

    @Test
    void requestIdIsGeneratedWhenMissing() {
        Res res = get("/accounts/" + UUID.randomUUID());
        String requestId = res.error().get("request_id").asText();
        UUID.fromString(requestId);
        assertThat(res.raw().getHeader("X-Request-ID")).isEqualTo(requestId);
    }

    @Test
    void unknownRouteUsesSameErrorShape() {
        Res res = get("/nope");
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void healthChecksTheDatabase() {
        Res res = get("/health");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("status").asText()).isEqualTo("ok");
    }

    @Test
    void openApiDocumentsErrorsAndMoneyAsString() {
        Res res = get("/v3/api-docs");
        assertThat(res.status()).isEqualTo(200);
        var createAccount = res.body().at("/paths/~1accounts/post/responses");
        assertThat(createAccount.has("201")).isTrue();
        assertThat(createAccount.has("400")).isTrue();
        assertThat(res.body().at("/components/schemas/Account/properties/balance/type").asText()).isEqualTo("string");
        assertThat(res.body().at("/components/schemas/CreateTransactionRequest/properties").has("reference_id")).isTrue();
        assertThat(res.body().at("/components/schemas/ErrorResponse").isMissingNode()).isFalse();
    }
}
