package com.confluencr.ledger.config;

import com.confluencr.ledger.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;

/** Swagger UI at /swagger-ui, spec at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    static {
        // Amounts are strings in JSON (see JacksonConfig), so document them as strings.
        SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, new StringSchema().example("150.00"));
    }

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Transaction Ledger API")
                .version("1.0.0")
                .description("Accounts, idempotent credit/debit transactions, paginated history and atomic transfers."));
    }

    /** Build schemas with the application's ObjectMapper, so the spec shows snake_case field names. */
    @Bean
    ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    /** Registers the ErrorResponse schema so the error responses below can reference it. */
    @Bean
    OpenApiCustomizer errorSchema() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(ErrorResponse.class);
            schemas.forEach(openApi.getComponents()::addSchemas);
        };
    }

    /** Adds one documented error response per status listed in the endpoint's @ApiErrors. */
    @Bean
    OperationCustomizer apiErrors() {
        return (operation, handlerMethod) -> {
            ApiErrors errors = handlerMethod.getMethodAnnotation(ApiErrors.class);
            if (errors != null) {
                for (int status : errors.value()) {
                    Schema<?> ref = new Schema<>().$ref("#/components/schemas/ErrorResponse");
                    operation.getResponses().addApiResponse(String.valueOf(status), new ApiResponse()
                            .description(HttpStatus.valueOf(status).getReasonPhrase())
                            .content(new Content().addMediaType("application/json", new MediaType().schema(ref))));
                }
            }
            return operation;
        };
    }
}
