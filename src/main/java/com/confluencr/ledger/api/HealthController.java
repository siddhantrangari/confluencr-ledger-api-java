package com.confluencr.ledger.api;

import com.confluencr.ledger.api.Responses.Health;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "ops")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Liveness plus a database round trip; fails with 500 if Postgres is unreachable. */
    @GetMapping("/health")
    public Health health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new Health("ok");
    }
}
