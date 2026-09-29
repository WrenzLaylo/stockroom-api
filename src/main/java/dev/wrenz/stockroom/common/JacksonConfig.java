package dev.wrenz.stockroom.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /** Quantities and IDs must be whole numbers: fail on 1.5 (and 1.0) instead of silently truncating. */
    @Bean
    JsonMapperBuilderCustomizer rejectDecimalsForWholeNumbers() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
