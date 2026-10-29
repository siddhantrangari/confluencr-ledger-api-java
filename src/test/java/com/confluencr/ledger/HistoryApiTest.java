package com.confluencr.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryApiTest extends IntegrationTest {

    private String acc;

    /** 7 transactions in this order: c1 d1 c2 c3 d2 c4 c5. */
    @BeforeEach
    void accountWithHistory() {
        acc = createAccount();
        for (String ref : List.of("c1", "d1", "c2", "c3", "d2", "c4", "c5")) {
            boolean credit = ref.startsWith("c");
            assertThat(postTxn(acc, credit ? "credit" : "debit", credit ? 100 : 10, ref).status()).isEqualTo(201);
        }
    }

    private Res history(String query) {
        return get("/accounts/" + acc + "/transactions" + query);
    }

    private static List<String> refs(Res res) {
        List<String> refs = new ArrayList<>();
        res.body().get("data").forEach(t -> refs.add(t.get("reference_id").asText()));
        return refs;
    }

    private static String pagination(int page, int limit, int total, int pages) {
        return "{\"page\":%d,\"limit\":%d,\"total_records\":%d,\"total_pages\":%d}".formatted(page, limit, total, pages);
    }

    @Test
    void latestFirstWithDefaultPagination() {
        Res res = history("");
        assertThat(res.status()).isEqualTo(200);
        assertThat(refs(res)).containsExactly("c5", "c4", "d2", "c3", "c2", "d1", "c1");
        assertThat(res.body().get("pagination").toString()).isEqualTo(pagination(1, 20, 7, 1));
    }

    @Test
    void createdAtIsNonIncreasing() {
        List<String> times = new ArrayList<>();
        history("").body().get("data").forEach(t -> times.add(t.get("created_at").asText()));
        assertThat(times).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void paginationPagesThroughEverythingOnce() {
        assertThat(refs(history("?page=1&limit=3"))).containsExactly("c5", "c4", "d2");
        assertThat(refs(history("?page=2&limit=3"))).containsExactly("c3", "c2", "d1");
        Res last = history("?page=3&limit=3");
        assertThat(refs(last)).containsExactly("c1");
        assertThat(last.body().get("pagination").toString()).isEqualTo(pagination(3, 3, 7, 3));
    }

    @Test
    void pagePastTheEndIsEmptyNotAnError() {
        Res res = history("?page=99&limit=3");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("data")).isEmpty();
        assertThat(res.body().get("pagination").get("total_pages").asInt()).isEqualTo(3);
    }

    @Test
    void filterByType() {
        Res debits = history("?type=debit");
        assertThat(refs(debits)).containsExactly("d2", "d1");
        assertThat(debits.body().get("pagination").get("total_records").asInt()).isEqualTo(2);
        Res credits = history("?type=credit&limit=2");
        assertThat(refs(credits)).containsExactly("c5", "c4");
        assertThat(credits.body().get("pagination").toString()).isEqualTo(pagination(1, 2, 5, 3));
    }

    @Test
    void emptyHistory() {
        String empty = createAccount("Empty");
        JsonNode body = get("/accounts/" + empty + "/transactions").body();
        assertThat(body.toString()).isEqualTo("{\"data\":[],\"pagination\":" + pagination(1, 20, 0, 0) + "}");
    }

    @Test
    void historyOnlyShowsOwnAccount() {
        String other = createAccount("Other");
        postTxn(other, "credit", 1, "c1");
        assertThat(get("/accounts/" + other + "/transactions").body().get("pagination").get("total_records").asInt())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=-1", "page=abc", "page=1.5", "page=1000001",
            "limit=0", "limit=101", "limit=ten"})
    void invalidPagination(String query) {
        Res res = history("?" + query);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_PAGINATION");
    }

    @Test
    void invalidTypeFilter() {
        Res res = history("?type=refund");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.errorCode()).isEqualTo("INVALID_TRANSACTION_TYPE");
    }

    @Test
    void historyOfUnknownAccountIs404() {
        Res res = get("/accounts/" + UUID.randomUUID() + "/transactions");
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }
}
