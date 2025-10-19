# Spring Boot OpenTelemetry with Tempo and Grafana

A Spring Boot application with distributed tracing using OpenTelemetry, Grafana Tempo, and Grafana for visualization.

## Prerequisites

- Java 17
- Docker and Docker Compose
- Gradle

## Architecture

- **Application**: Spring Boot 3.5.6 with OpenTelemetry auto-instrumentation
- **Trace Backend**: Grafana Tempo (receives and stores traces)
- **Visualization**: Grafana (displays traces)
- **Protocol**: OTLP HTTP on port 4318

## Project Structure

```
tel/
├── src/main/java/dev/siegfred/tel/
│   ├── TelApplication.java
│   └── controller/
│       └── GreetController.java
├── src/main/resources/
│   └── application.properties
├── docker-compose.yml
├── tempo-config.yaml
├── grafana-datasources.yaml
└── build.gradle
```

## Setup Steps

### 1. Dependencies (build.gradle)

Add OpenTelemetry and actuator dependencies:

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

### 2. Application Configuration (application.properties)

```properties
spring.application.name=tel
server.port=8080

# OpenTelemetry Configuration
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# Disable metrics and logs
management.metrics.export.otlp.enabled=false
management.otlp.logs.export.enabled=false
```

### 3. REST Controller

Create `src/main/java/dev/siegfred/tel/controller/GreetController.java`:

```java
package dev.siegfred.tel.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class GreetController {

    @GetMapping("/greet")
    public Map<String, String> greet() {
        return Map.of("message", "hello");
    }
}
```

### 4. Tempo Configuration (tempo-config.yaml)

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318
        grpc:
          endpoint: 0.0.0.0:4317

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
```

### 5. Grafana Datasource (grafana-datasources.yaml)

```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    uid: tempo
    isDefault: true
    editable: true
```

### 6. Docker Compose (docker-compose.yml)

```yaml
version: '3.8'

services:
  tempo:
    image: grafana/tempo:latest
    command: [ "-config.file=/etc/tempo.yaml" ]
    volumes:
      - ./tempo-config.yaml:/etc/tempo.yaml
      - tempo-data:/var/tempo
    ports:
      - "3200:3200"   # tempo
      - "4317:4317"   # otlp grpc
      - "4318:4318"   # otlp http

  grafana:
    image: grafana/grafana:latest
    volumes:
      - ./grafana-datasources.yaml:/etc/grafana/provisioning/datasources/datasources.yaml
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
      - GF_AUTH_DISABLE_LOGIN_FORM=true
    ports:
      - "3000:3000"
    depends_on:
      - tempo

volumes:
  tempo-data:
```

## Running the Application

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- Tempo on port 3200 (API), 4317 (gRPC), 4318 (HTTP)
- Grafana on port 3000

### 2. Start Application

```bash
./gradlew bootRun
```

The application starts on port 8080.

### 3. Generate Traces

```bash
curl http://localhost:8080/api/greet
```

Response:
```json
{"message":"hello"}
```

### 4. View Traces in Grafana

1. Open browser: http://localhost:3000
2. Click **Explore** (compass icon)
3. Select **Tempo** datasource
4. Use **Search** or query:
   ```
   { service.name="tel" }
   ```

## Verification

### Check Tempo has traces:

```bash
curl -s "http://localhost:3200/api/search?tags=service.name%3Dtel" | jq '.'
```

### Check application status:

```bash
curl http://localhost:8080/actuator/health
```

## Key Features

✅ **Automatic Instrumentation**: Spring Boot auto-configures OpenTelemetry
✅ **Trace Sampling**: 100% sampling for development
✅ **OTLP HTTP**: Uses standard OTLP protocol
✅ **No Login Required**: Grafana anonymous access enabled
✅ **Traces Only**: Metrics and logs export disabled

## Troubleshooting

### Application not sending traces

1. Verify Tempo is running:
   ```bash
   docker-compose ps
   ```

2. Check Tempo logs:
   ```bash
   docker-compose logs tempo
   ```

3. Restart application after Tempo is ready:
   ```bash
   ./gradlew bootRun
   ```

### Port conflicts

If ports are in use, update `docker-compose.yml` and `application.properties` accordingly.

## Technology Stack

- **Spring Boot**: 3.5.6
- **Java**: 17
- **OpenTelemetry**: Auto-configured via Spring Boot
- **Grafana Tempo**: Latest
- **Grafana**: Latest (v12.1.1)
- **Protocol**: OTLP HTTP
- **Build Tool**: Gradle
