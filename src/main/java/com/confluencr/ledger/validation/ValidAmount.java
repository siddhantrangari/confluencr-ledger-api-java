package com.confluencr.ledger.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A money amount: greater than 0, at most 1,000,000,000.00, at most 2 decimal places.
 * Null passes here, so pair it with @NotNull. One annotation, so transactions and transfers
 * can never drift apart on the rules.
 */
@DecimalMin(value = "0", inclusive = false)
@DecimalMax(ValidAmount.MAX)
@Digits(integer = 10, fraction = 2)
@Constraint(validatedBy = {})
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAmount {

    String MAX = "1000000000.00";

    String message() default "invalid amount";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
