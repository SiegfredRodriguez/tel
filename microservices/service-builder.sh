#!/bin/bash

# This script generates 4 microservices that chain together

SERVICES=("service-a" "service-b" "service-c" "service-d")
PORTS=(8081 8082 8083 8084)

for i in "${!SERVICES[@]}"; do
  SERVICE="${SERVICES[$i]}"
  PORT="${PORTS[$i]}"
  NEXT_PORT=$((PORT + 1))

  echo "Creating $SERVICE on port $PORT..."

  mkdir -p "$SERVICE/src/main/java/dev/siegfred/$SERVICE"
  mkdir -p "$SERVICE/src/main/resources"
  mkdir -p "$SERVICE/logs"

  # Create build.gradle
  cat > "$SERVICE/build.gradle" <<EOF
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.6'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'dev.siegfred'
version = '1.0.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'
    implementation 'io.micrometer:micrometer-registry-otlp'
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
EOF

  # Create settings.gradle
  cat > "$SERVICE/settings.gradle" <<EOF
rootProject.name = '$SERVICE'
EOF

  # Create application.properties
  cat > "$SERVICE/src/main/resources/application.properties" <<EOF
spring.application.name=$SERVICE
server.port=$PORT

# OpenTelemetry Tracing
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://otel-collector:4318/v1/traces

# OpenTelemetry Metrics
management.metrics.export.otlp.enabled=true
management.metrics.export.otlp.url=http://otel-collector:4318/v1/metrics
management.metrics.export.otlp.step=10s

# Enable metrics
management.metrics.enable.jvm=true
management.metrics.enable.process=true
management.metrics.enable.system=true

# Resource attributes
otel.resource.attributes=service.name=$SERVICE,service.version=1.0.0,deployment.environment=development

# Logging
logging.pattern.level=%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]
EOF

  # Create logback-spring.xml
  cat > "$SERVICE/src/main/resources/logback-spring.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [traceId=%X{traceId:-} spanId=%X{spanId:-}] - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="JSON_FILE"/>
    </root>
</configuration>
EOF

  # Determine if this service calls the next one
  if [ "$i" -lt 3 ]; then
    NEXT_SERVICE="${SERVICES[$((i + 1))]}"
    CALLS_NEXT="true"
  else
    NEXT_SERVICE=""
    CALLS_NEXT="false"
  fi

  # Create Application class
  cat > "$SERVICE/src/main/java/dev/siegfred/$SERVICE/Application.java" <<EOF
package dev.siegfred.${SERVICE//-/};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }
}
EOF

  # Create Controller
  PACKAGE_NAME="${SERVICE//-/}"
  cat > "$SERVICE/src/main/java/dev/siegfred/$SERVICE/Controller.java" <<EOF
package dev.siegfred.${PACKAGE_NAME};

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class Controller {

    private final RestClient restClient;

    public Controller(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping("/process")
    public Map<String, Object> process(@RequestParam(defaultValue = "data") String data) {
        log.info("[$SERVICE] Received request with data: {}", data);

        Map<String, Object> response = new HashMap<>();
        response.put("service", "$SERVICE");
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());

EOF

  if [ "$CALLS_NEXT" = "true" ]; then
    cat >> "$SERVICE/src/main/java/dev/siegfred/$SERVICE/Controller.java" <<EOF
        // Call next service in the chain
        try {
            log.info("[$SERVICE] Calling next service: $NEXT_SERVICE");
            String nextServiceUrl = "http://$NEXT_SERVICE:$NEXT_PORT/api/process?data=" + data;
            Map<String, Object> nextResponse = restClient.get()
                .uri(nextServiceUrl)
                .retrieve()
                .body(Map.class);
            response.put("next", nextResponse);
            log.info("[$SERVICE] Successfully received response from $NEXT_SERVICE");
        } catch (Exception e) {
            log.error("[$SERVICE] Error calling next service", e);
            response.put("error", "Failed to call $NEXT_SERVICE: " + e.getMessage());
        }
EOF
  else
    cat >> "$SERVICE/src/main/java/dev/siegfred/$SERVICE/Controller.java" <<EOF
        log.info("[$SERVICE] End of chain reached");
        response.put("message", "End of chain");
EOF
  fi

  cat >> "$SERVICE/src/main/java/dev/siegfred/$SERVICE/Controller.java" <<EOF

        log.info("[$SERVICE] Returning response");
        return response;
    }
}
EOF

  # Create Dockerfile
  cat > "$SERVICE/Dockerfile" <<EOF
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle build -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/logs
EXPOSE $PORT
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF

  # Create gradlew wrapper files
  cp ../../gradle ./gradle -r 2>/dev/null || true
  cp ../../gradlew ./$SERVICE/ 2>/dev/null || true
  cp ../../gradlew.bat ./$SERVICE/ 2>/dev/null || true

done

echo "All services created!"
EOF
