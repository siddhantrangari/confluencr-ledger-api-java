package com.confluencr.ledger.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

@Configuration
public class JacksonConfig {

    /**
     * Money goes out as a JSON string ("150.00"), never a JSON number. Many JSON parsers
     * (JavaScript included) read numbers as doubles, which can't hold every decimal exactly.
     */
    @Bean
    SimpleModule moneyAsStringModule() {
        SimpleModule module = new SimpleModule("money-as-string");
        module.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toPlainString());
            }
        });
        return module;
    }

    /**
     * Jackson by default turns 123 or true into "123" / "true" for a String field. Here a
     * name, type or reference_id must actually be a JSON string, otherwise it's a 400.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictStrings() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
