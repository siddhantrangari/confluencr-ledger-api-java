package com.confluencr.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Base class for every test: the real application against a real PostgreSQL 16 in Docker.
 * One container is started for the whole test run and every table is emptied before each test.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=25") // room for 20 parallel workers
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Plain mapper for writing test request bodies exactly as given (keys are already snake_case). */
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE does not fire the row-level "no DELETE" trigger, so tests can still reset the data.
        jdbc.execute("TRUNCATE transactions, accounts");
    }

    // ---------- HTTP helpers ----------

    protected record Res(int status, JsonNode body, MockHttpServletResponse raw) {

        JsonNode error() {
            return body.get("error");
        }

        String errorCode() {
            return body.get("error").get("code").asText();
        }
    }

    protected Res get(String url) {
        return call(request(HttpMethod.GET, url));
    }

    protected Res post(String url, Object body) {
        return send(HttpMethod.POST, url, body);
    }

    protected Res send(HttpMethod method, String url, Object body) {
        try {
            String content = body instanceof String s ? s : json.writeValueAsString(body);
            return call(request(method, url).contentType(MediaType.APPLICATION_JSON).content(content));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Res call(MockHttpServletRequestBuilder builder) {
        try {
            MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
            String text = response.getContentAsString();
            JsonNode node = text.isEmpty() ? null : json.readTree(text);
            return new Res(response.getStatus(), node, response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A map that allows null values, for request bodies. */
    protected static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ---------- domain helpers ----------

    protected String createAccount(String name) {
        Res res = post("/accounts", body("name", name));
        assertThat(res.status()).isEqualTo(201);
        return res.body().get("id").asText();
    }

    protected String createAccount() {
        return createAccount("Alice");
    }

    protected Res postTxn(String accountId, Object type, Object amount, Object ref) {
        return post("/accounts/" + accountId + "/transactions", body("type", type, "amount", amount, "reference_id", ref));
    }

    protected Res postTxn(String accountId, Object type, Object amount, Object ref, String description) {
        return post("/accounts/" + accountId + "/transactions",
                body("type", type, "amount", amount, "reference_id", ref, "description", description));
    }

    protected BigDecimal balanceOf(String accountId) {
        return new BigDecimal(get("/accounts/" + accountId + "/balance").body().get("balance").asText());
    }

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** The core invariant: stored balance == sum(credits) - sum(debits), and never negative. */
    protected BigDecimal assertLedgerConsistent(String accountId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT a.balance,
                       COALESCE(SUM(CASE t.type WHEN 'credit' THEN t.amount ELSE -t.amount END), 0) AS computed
                FROM accounts a LEFT JOIN transactions t ON t.account_id = a.id
                WHERE a.id = ? GROUP BY a.balance""", UUID.fromString(accountId));
        BigDecimal balance = (BigDecimal) row.get("balance");
        assertThat(balance).isEqualByComparingTo((BigDecimal) row.get("computed"));
        assertThat(balance.signum()).isGreaterThanOrEqualTo(0);
        return balance;
    }
}
