package dev.wrenz.stockroom.security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** BCrypt only reads the first 72 BYTES of a password. Reject longer ones instead of silently truncating. */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8MaxBytesValidator.class)
public @interface Utf8MaxBytes {
    int value();
    String message() default "must be at most {value} bytes when UTF-8 encoded";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
