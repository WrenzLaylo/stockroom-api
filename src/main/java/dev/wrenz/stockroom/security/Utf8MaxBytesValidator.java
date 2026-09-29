package dev.wrenz.stockroom.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class Utf8MaxBytesValidator implements ConstraintValidator<Utf8MaxBytes, String> {

    private int maxBytes;

    public Utf8MaxBytesValidator() {}

    Utf8MaxBytesValidator(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public void initialize(Utf8MaxBytes annotation) {
        this.maxBytes = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
