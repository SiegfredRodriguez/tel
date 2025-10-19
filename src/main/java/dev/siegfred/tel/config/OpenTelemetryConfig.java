package dev.siegfred.tel.config;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfig {

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${otel.resource.attributes.created.by:Unknown}")
    private String createdBy;

    @Value("${otel.resource.attributes.built.with:Unknown}")
    private String builtWith;

    @Value("${otel.resource.attributes.service.version:1.0.0}")
    private String serviceVersion;

    @Bean
    public Resource otelResource() {
        AttributesBuilder attributesBuilder = Attributes.builder()
                .put(ServiceAttributes.SERVICE_NAME, applicationName)
                .put(ServiceAttributes.SERVICE_VERSION, serviceVersion)
                .put("created.by", createdBy)
                .put("built.with", builtWith);

        return Resource.create(attributesBuilder.build());
    }
}
