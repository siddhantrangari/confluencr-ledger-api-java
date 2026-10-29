package com.confluencr.ledger.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The error statuses an endpoint can return; OpenApiConfig adds them to the spec with the ErrorResponse schema. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrors {

    int[] value();
}
