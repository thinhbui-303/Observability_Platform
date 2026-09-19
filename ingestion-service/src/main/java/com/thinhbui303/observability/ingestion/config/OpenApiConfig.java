package com.thinhbui303.observability.ingestion.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Ingestion Service API",
                version = "v1",
                description = "API for ingesting telemetry data (logs) into the Observability Platform."
        )
)
@SecurityScheme(
        name = "X-API-Key",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key",
        description = "API Key authentication. Required for all endpoints."
)
public class OpenApiConfig {
}
