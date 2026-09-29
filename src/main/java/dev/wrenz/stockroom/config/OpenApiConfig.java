package dev.wrenz.stockroom.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Swagger UI is an interactive demo: log in with a demo account, then "Authorize" with the token. */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Stockroom API", version = "1.0",
                description = "Multi-tenant inventory and orders for small shops. Stock can't go negative, even with concurrent orders."),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
