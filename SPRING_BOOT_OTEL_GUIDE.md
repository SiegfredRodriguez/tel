# Complete Guide: Integrating OpenTelemetry with Spring Boot from Scratch

**Build a fully observable Spring Boot application with distributed tracing, metrics, and structured logging**

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Infrastructure Setup](#infrastructure-setup)
4. [Part 1: Traces](#part-1-traces)
5. [Part 2: Metrics](#part-2-metrics)
6. [Part 3: Logs](#part-3-logs)
7. [Part 4: RabbitMQ Trace Propagation](#part-4-rabbitmq-trace-propagation)
8. [Part 5: Custom Metadata](#part-5-custom-metadata)
9. [Real Trace Examples](#real-trace-examples)
10. [Testing & Verification](#testing--verification)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         YOUR APPLICATION                                 │
│  ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐        │
│  │  Gateway  │──▶│ Service-A │──▶│ Service-B │──▶│ Service-C │─┐      │
│  └───────────┘   └───────────┘   └───────────┘   └───────────┘ │      │
│       │                                                          │      │
│       │ Publishes                                                │      │
│       ▼                                                          ▼      │
│  ┌──────────┐                                              ┌──────────┐ │
│  │ RabbitMQ │                                              │Service-D │ │
│  │ Fanout   │──┬─▶ Consumer-A                             └──────────┘ │
│  │ Exchange │  ├─▶ Consumer-B                                   │      │
│  └──────────┘  └─▶ Consumer-C                                   ▼      │
│                                                          ┌──────────┐   │
│                                                          │Service-E │   │
│                                                          │(RabbitMQ │   │
│                                                          │Consumer) │   │
│                                                          └──────────┘   │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │ All services emit: TRACES + METRICS + LOGS                     │    │
│  └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ OTLP (gRPC/HTTP)
                                    ▼
                      ┌──────────────────────────┐
                      │   OTEL Collector         │
                      │  (Receives & Routes)     │
                      └──────────────────────────┘
                                    │
                 ┌──────────────────┼──────────────────┐
                 │                  │                  │
        ┌────────▼────────┐ ┌──────▼──────┐  ┌───────▼────────┐
        │  Grafana Tempo  │ │ Prometheus  │  │  Grafana Loki  │
        │    (Traces)     │ │  (Metrics)  │  │    (Logs)      │
        └─────────────────┘ └─────────────┘  └────────────────┘
                 │                  │                  │
                 └──────────────────┼──────────────────┘
                                    │
                          ┌─────────▼─────────┐
                          │  Grafana (UI)     │
                          │ Visualize All     │
                          └───────────────────┘

```

**Key Concepts:**
- **OTLP (OpenTelemetry Protocol)**: Standard protocol for exporting telemetry data
- **OTEL Collector**: Central hub that receives, processes, and routes telemetry
- **Trace Context Propagation**: Trace IDs and span IDs flow through HTTP and RabbitMQ
- **Automatic Instrumentation**: Spring Boot auto-configures OpenTelemetry integration

---

## Prerequisites

### Required Tools
```bash
# Java 17 or higher
java -version

# Docker & Docker Compose
docker --version
docker-compose --version

# Gradle (optional, wrapper included)
./gradlew --version
```

### Technology Stack
- **Spring Boot**: 3.5.6
- **Spring Cloud**: 2024.0.0
- **Micrometer Tracing**: Bridge for OpenTelemetry
- **OpenTelemetry**: 1.x (latest)
- **RabbitMQ**: 3.13
- **Grafana Stack**: Tempo, Loki, Prometheus, Grafana

---

## Infrastructure Setup

### Step 1: Create `docker-compose.yml`

```yaml
version: '3.8'

services:
  # ============================================================================
  # OPENTELEMETRY COLLECTOR - Central telemetry receiver
  # ============================================================================
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.97.0
    container_name: otel-collector
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"   # OTLP gRPC receiver
      - "4318:4318"   # OTLP HTTP receiver
      - "8888:8888"   # Prometheus metrics (collector's own metrics)
    networks:
      - observability

  # ============================================================================
  # GRAFANA TEMPO - Distributed tracing backend
  # ============================================================================
  tempo:
    image: grafana/tempo:2.4.1
    container_name: tempo
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./tempo-config.yaml:/etc/tempo.yaml
      - tempo-data:/tmp/tempo
    ports:
      - "3200:3200"   # Tempo HTTP API
      - "9095:9095"   # Tempo gRPC
      - "4319:4317"   # OTLP gRPC (receives from collector)
    networks:
      - observability

  # ============================================================================
  # PROMETHEUS - Metrics storage and querying
  # ============================================================================
  prometheus:
    image: prom/prometheus:v2.51.0
    container_name: prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.enable-remote-write-receiver'
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    networks:
      - observability

  # ============================================================================
  # LOKI - Log aggregation system
  # ============================================================================
  loki:
    image: grafana/loki:2.9.6
    container_name: loki
    command: -config.file=/etc/loki/local-config.yaml
    ports:
      - "3100:3100"
    networks:
      - observability

  # ============================================================================
  # GRAFANA - Unified observability UI
  # ============================================================================
  grafana:
    image: grafana/grafana:10.4.1
    container_name: grafana
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
      - GF_AUTH_DISABLE_LOGIN_FORM=true
    volumes:
      - ./grafana-datasources.yml:/etc/grafana/provisioning/datasources/datasources.yml
      - grafana-data:/var/lib/grafana
    ports:
      - "3000:3000"
    networks:
      - observability
    depends_on:
      - tempo
      - prometheus
      - loki

  # ============================================================================
  # RABBITMQ - Message broker with management UI
  # ============================================================================
  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: rabbitmq
    ports:
      - "5672:5672"    # AMQP protocol
      - "15672:15672"  # Management UI
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    networks:
      - observability

volumes:
  tempo-data:
  prometheus-data:
  grafana-data:

networks:
  observability:
    driver: bridge
```

### Step 2: Configure OTEL Collector (`otel-collector-config.yaml`)

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 10s
    send_batch_size: 1024

exporters:
  # Export traces to Tempo
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true

  # Export metrics to Prometheus
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write
    tls:
      insecure: true

  # Export logs to Loki
  loki:
    endpoint: http://loki:3100/loki/api/v1/push

  # Debug logging (optional)
  logging:
    loglevel: info

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/tempo, logging]

    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [prometheusremotewrite]

    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [loki]
```

### Step 3: Configure Tempo (`tempo-config.yaml`)

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317

storage:
  trace:
    backend: local
    local:
      path: /tmp/tempo/blocks

query_frontend:
  search:
    enabled: true
```

### Step 4: Configure Prometheus (`prometheus.yml`)

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'gateway'
    static_configs:
      - targets: ['gateway:8080']
    metrics_path: '/actuator/prometheus'

  - job_name: 'service-a'
    static_configs:
      - targets: ['service-a:8081']
    metrics_path: '/actuator/prometheus'

  - job_name: 'service-b'
    static_configs:
      - targets: ['service-b:8082']
    metrics_path: '/actuator/prometheus'

  - job_name: 'service-c'
    static_configs:
      - targets: ['service-c:8083']
    metrics_path: '/actuator/prometheus'

  - job_name: 'service-d'
    static_configs:
      - targets: ['service-d:8084']
    metrics_path: '/actuator/prometheus'

  - job_name: 'service-e'
    static_configs:
      - targets: ['service-e:8085']
    metrics_path: '/actuator/prometheus'
```

### Step 5: Configure Grafana Datasources (`grafana-datasources.yml`)

```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    isDefault: true
    jsonData:
      httpMethod: GET
      tracesToLogs:
        datasourceUid: 'loki'
      tracesToMetrics:
        datasourceUid: 'prometheus'
      serviceMap:
        datasourceUid: 'prometheus'

  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    jsonData:
      httpMethod: POST

  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    jsonData:
      derivedFields:
        - datasourceUid: tempo
          matcherRegex: "traceId=(\\w+)"
          name: TraceID
          url: '$${__value.raw}'
```

### Step 6: Start Infrastructure

```bash
docker-compose up -d

# Verify all services are running
docker-compose ps

# Check logs
docker-compose logs -f otel-collector
```

**Access URLs:**
- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090
- RabbitMQ Management: http://localhost:15672 (guest/guest)
- Tempo API: http://localhost:3200

---

## Part 1: Traces

### Step 1: Create Spring Boot Project

```bash
spring init --dependencies=web,actuator tel
cd tel
```

### Step 2: Add OpenTelemetry Dependencies (`build.gradle`)

```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.6'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'dev.siegfred'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

ext {
    springCloudVersion = '2024.0.0'
}

dependencies {
    // Spring Boot starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // ============================================================================
    // TRACING: OpenTelemetry integration via Micrometer
    // ============================================================================
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'

    // ============================================================================
    // HTTP CLIENT: Feign with trace propagation
    // ============================================================================
    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
    implementation 'org.springframework.cloud:spring-cloud-starter-loadbalancer'
    implementation 'io.github.openfeign:feign-micrometer'

    // ============================================================================
    // MESSAGING: RabbitMQ with AMQP
    // ============================================================================
    implementation 'org.springframework.boot:spring-boot-starter-amqp'

    // ============================================================================
    // METRICS: Prometheus + OTLP exporters
    // ============================================================================
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.micrometer:micrometer-registry-otlp'

    // ============================================================================
    // LOGS: Structured logging with JSON encoder
    // ============================================================================
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
    }
}
```

### Step 3: Configure Application (`application.yml`)

```yaml
spring:
  application:
    name: gateway  # IMPORTANT: This becomes service.name in traces
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

server:
  port: 8080

# ============================================================================
# OPENTELEMETRY CONFIGURATION
# ============================================================================
# Uses standard OTEL environment variables:
# - OTEL_EXPORTER_OTLP_ENDPOINT (e.g., http://otel-collector:4318)
# - OTEL_EXPORTER_OTLP_PROTOCOL (e.g., http/protobuf)
# - OTEL_RESOURCE_ATTRIBUTES (e.g., service.name=gateway,service.version=1.0.0)

otel:
  resource:
    attributes:
      created.by: "J"
      built.with: "Java"
      service.version: "1.0.0"

management:
  # ============================================================================
  # TRACING CONFIGURATION
  # ============================================================================
  tracing:
    sampling:
      probability: 1.0  # 1.0 = 100% sampling (sample all traces)

  # OTLP endpoint for traces
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces

  # ============================================================================
  # METRICS CONFIGURATION
  # ============================================================================
  metrics:
    export:
      # OTLP metrics export
      otlp:
        enabled: true
        url: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics
        step: 10s

      # Prometheus metrics (backwards compatibility)
      prometheus:
        enabled: true

    distribution:
      percentiles-histogram:
        http:
          server:
            requests: true

    # Enable JVM and system metrics
    enable:
      jvm: true
      process: true
      system: true

  # ============================================================================
  # ACTUATOR ENDPOINTS
  # ============================================================================
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics

# ============================================================================
# LOGGING - Include trace context in MDC
# ============================================================================
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

### Step 4: Configure Custom Resource Attributes

Create `src/main/java/dev/siegfred/tel/config/OpenTelemetryConfig.java`:

```java
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

    /**
     * Creates OpenTelemetry Resource with custom attributes.
     * Resource attributes appear on ALL spans from this service.
     */
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
```

### Step 5: Create a Simple Controller

Create `src/main/java/dev/siegfred/tel/controller/GreetController.java`:

```java
package dev.siegfred.tel.controller;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class GreetController {

    @Autowired
    private Tracer tracer;

    @GetMapping("/api/greet")
    public Map<String, Object> greet(
            @RequestParam(required = false, defaultValue = "World") String name) {

        // Get current span to add custom attributes
        Span currentSpan = tracer.currentSpan();

        if (currentSpan != null) {
            // Add custom span attributes (specific to this operation)
            currentSpan.tag("request.name", name);
            currentSpan.tag("handler.method", "greet");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Hello, " + name + "!");
        response.put("service", "gateway");
        response.put("timestamp", System.currentTimeMillis());

        return response;
    }
}
```

### Step 6: Run and Test

```bash
# Build the application
./gradlew clean build

# Run the application
java -jar build/libs/tel-0.0.1-SNAPSHOT.jar

# Or with environment variables
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
OTEL_RESOURCE_ATTRIBUTES=service.name=gateway,service.version=1.0.0 \
java -jar build/libs/tel-0.0.1-SNAPSHOT.jar
```

Test the endpoint:
```bash
curl "http://localhost:8080/api/greet?name=OpenTelemetry"
```

### Step 7: View Traces in Grafana

1. Open http://localhost:3000
2. Go to **Explore** → Select **Tempo**
3. Search using TraceQL:
   ```
   {service.name="gateway"}
   {created.by="J"}
   {built.with="Java"}
   ```

**What you'll see:**
```
Root Span: http get /api/greet
├─ service.name: gateway
├─ created.by: J
├─ built.with: Java
├─ service.version: 1.0.0
└─ Span Attributes:
   ├─ request.name: OpenTelemetry
   └─ handler.method: greet
```

---

## Part 2: Metrics

### Understanding Metrics in OpenTelemetry

Spring Boot automatically exports metrics to both Prometheus and OTLP:

1. **JVM Metrics**: Memory, GC, threads
2. **HTTP Metrics**: Request count, duration, errors
3. **System Metrics**: CPU, disk, network
4. **Custom Metrics**: Business-specific counters/gauges

### Step 1: Verify Metrics Export

```bash
# Check Prometheus format endpoint
curl http://localhost:8080/actuator/prometheus

# Check metrics summary
curl http://localhost:8080/actuator/metrics
```

### Step 2: Add Custom Metrics

Create `src/main/java/dev/siegfred/tel/metrics/CustomMetrics.java`:

```java
package dev.siegfred.tel.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class CustomMetrics {

    private final Counter greetCounter;
    private final Timer greetTimer;

    public CustomMetrics(MeterRegistry registry) {
        // Counter: Total number of greet requests
        this.greetCounter = Counter.builder("app.greet.requests.total")
                .description("Total number of greet requests")
                .tag("service", "gateway")
                .register(registry);

        // Timer: Duration of greet operations
        this.greetTimer = Timer.builder("app.greet.duration")
                .description("Duration of greet operation")
                .tag("service", "gateway")
                .register(registry);
    }

    public void incrementGreetCounter() {
        greetCounter.increment();
    }

    public Timer getGreetTimer() {
        return greetTimer;
    }
}
```

Update controller to use custom metrics:

```java
@RestController
public class GreetController {

    @Autowired
    private Tracer tracer;

    @Autowired
    private CustomMetrics customMetrics;

    @GetMapping("/api/greet")
    public Map<String, Object> greet(
            @RequestParam(required = false, defaultValue = "World") String name) {

        // Record metric
        return customMetrics.getGreetTimer().record(() -> {
            customMetrics.incrementGreetCounter();

            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                currentSpan.tag("request.name", name);
                currentSpan.tag("handler.method", "greet");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Hello, " + name + "!");
            response.put("service", "gateway");
            response.put("timestamp", System.currentTimeMillis());

            return response;
        });
    }
}
```

### Step 3: Query Metrics in Prometheus

1. Open http://localhost:9090
2. Query examples:
   ```promql
   # Total greet requests
   app_greet_requests_total

   # Request rate per second
   rate(app_greet_requests_total[1m])

   # Average duration
   rate(app_greet_duration_sum[1m]) / rate(app_greet_duration_count[1m])

   # HTTP request duration
   http_server_requests_seconds_sum{uri="/api/greet"}
   ```

### Step 4: Create Grafana Dashboard

1. Go to Grafana → Dashboards → New Dashboard
2. Add panels:

**Panel 1: Request Rate**
```promql
sum(rate(http_server_requests_seconds_count{service="gateway"}[5m])) by (uri)
```

**Panel 2: Error Rate**
```promql
sum(rate(http_server_requests_seconds_count{service="gateway",status=~"5.."}[5m])) by (uri)
```

**Panel 3: Latency (p95)**
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{service="gateway"}[5m])) by (le, uri))
```

**Panel 4: JVM Memory**
```promql
jvm_memory_used_bytes{service="gateway"}
```

---

## Part 3: Logs

### Step 1: Configure Structured Logging with Logback

Create `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- Console Appender with JSON formatting -->
    <appender name="CONSOLE_JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <customFields>{"service":"${spring.application.name}"}</customFields>
        </encoder>
    </appender>

    <!-- Console Appender with human-readable format -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Choose JSON or human-readable based on profile -->
    <springProfile name="prod">
        <root level="INFO">
            <appender-ref ref="CONSOLE_JSON"/>
        </root>
    </springProfile>

    <springProfile name="!prod">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

### Step 2: Add Logging to Your Code

```java
package dev.siegfred.tel.controller;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class GreetController {

    private static final Logger log = LoggerFactory.getLogger(GreetController.class);

    @Autowired
    private Tracer tracer;

    @GetMapping("/api/greet")
    public Map<String, Object> greet(
            @RequestParam(required = false, defaultValue = "World") String name) {

        log.info("Received greet request for name: {}", name);

        Span currentSpan = tracer.currentSpan();

        if (currentSpan != null) {
            currentSpan.tag("request.name", name);
            currentSpan.tag("handler.method", "greet");

            log.debug("Processing in trace: {}, span: {}",
                     currentSpan.context().traceId(),
                     currentSpan.context().spanId());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Hello, " + name + "!");
        response.put("service", "gateway");
        response.put("timestamp", System.currentTimeMillis());

        log.info("Completed greet request for name: {}", name);

        return response;
    }
}
```

### Step 3: View Logs in Grafana Loki

**Log Output with Trace Context:**
```
2025-10-19 14:23:45.123  INFO [gateway,c5086f3bafafaedc915172729428f3d8,2c4MU7ghXjA] c.e.t.controller.GreetController - Received greet request for name: OpenTelemetry
2025-10-19 14:23:45.456  INFO [gateway,c5086f3bafafaedc915172729428f3d8,2c4MU7ghXjA] c.e.t.controller.GreetController - Completed greet request for name: OpenTelemetry
```

**Query in Grafana:**
1. Go to **Explore** → Select **Loki**
2. Query logs by trace ID:
   ```logql
   {service_name="gateway"} |= "c5086f3bafafaedc915172729428f3d8"
   ```
3. Click on a log line → Click **Tempo** button → Jump to trace!

---

## Part 4: RabbitMQ Trace Propagation

### Architecture: Chain Pattern

```
┌─────────┐   HTTP    ┌───────────┐   HTTP    ┌───────────┐   HTTP    ┌───────────┐
│ Gateway │ ────────▶ │ Service-A │ ────────▶ │ Service-B │ ────────▶ │ Service-C │
└─────────┘           └───────────┘           └───────────┘           └───────────┘
     │                                                                       │
     │                                                                       │
     │                                                                       ▼
     │                                                               ┌───────────┐
     │                                                               │ Service-D │
     │                                                               └───────────┘
     │                                                                       │
     │                                                                       │ Publish
     │                                                                       ▼
     │                                                               ┌──────────────┐
     │                                                               │  RabbitMQ    │
     │                                                               │ Chain Queue  │
     │                                                               └──────────────┘
     │                                                                       │
     │                                                                       │ Consume
     │                                                                       ▼
     │                                                               ┌───────────┐
     └───────────────────── TRACE CONTEXT FLOWS ──────────────────▶│ Service-E │
                            (traceId + spanId)                      └───────────┘

TraceID: c5086f3bafafaedc915172729428f3d8 (SAME across all services!)
```

### Architecture: Fan-Out Pattern

```
                                                          ┌─────────────────┐
                                                          │   Service-E     │
                                                          │  Consumer-A     │
                                                          │ (Queue A)       │
                                                          └─────────────────┘
                                                                   ▲
                                                                   │
┌─────────┐                    ┌──────────────────┐              │
│ Gateway │ ─── Publish ────▶  │   RabbitMQ       │ ─── Fanout ──┤
└─────────┘                    │ Fanout Exchange  │              │
                               └──────────────────┘              │
                                                                   │
                                                          ┌─────────────────┐
                                                          │   Service-E     │
                                                          │  Consumer-B     │
                                                          │ (Queue B)       │
                                                          └─────────────────┘
                                                                   ▲
                                                                   │
                                                                   │
                                                          ┌─────────────────┐
                                                          │   Service-E     │
                                                          │  Consumer-C     │
                                                          │ (Queue C)       │
                                                          └─────────────────┘

TraceID: 214dd5cbc438819c8956e27f4617bf1b (SAME across all 3 consumers!)
Spans:
├─ gateway: http get /api/fanout (parent)
├─ gateway: tel.fanout.exchange/ send (producer)
├─ service-e: tel.fanout.queue.a receive (consumer-A) ┐
├─ service-e: tel.fanout.queue.b receive (consumer-B) ├─ PARALLEL
└─ service-e: tel.fanout.queue.c receive (consumer-C) ┘
```

### Step 1: Configure RabbitMQ

Create `src/main/java/dev/siegfred/tel/config/RabbitMQConfig.java`:

```java
package dev.siegfred.tel.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration with automatic trace propagation via Micrometer Observation.
 *
 * KEY CONCEPTS:
 * 1. setObservationEnabled(true) - Enables automatic trace context injection/extraction
 * 2. RabbitTemplate - Publishes messages with trace headers
 * 3. RabbitListener - Extracts trace context and creates child spans
 */
@Configuration
@ConditionalOnProperty(name = "spring.rabbitmq.host")
public class RabbitMQConfig {

    // Chain pattern queue
    public static final String QUEUE_NAME = "tel.chain.queue";

    // Fan-out pattern exchange and queues
    public static final String FANOUT_EXCHANGE = "tel.fanout.exchange";
    public static final String FANOUT_QUEUE_A = "tel.fanout.queue.a";
    public static final String FANOUT_QUEUE_B = "tel.fanout.queue.b";
    public static final String FANOUT_QUEUE_C = "tel.fanout.queue.c";

    // ========================================================================
    // QUEUE DEFINITIONS
    // ========================================================================

    @Bean
    public Queue chainQueue() {
        return new Queue(QUEUE_NAME, true); // durable=true
    }

    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE, true, false);
    }

    @Bean
    public Queue fanoutQueueA() {
        return new Queue(FANOUT_QUEUE_A, true);
    }

    @Bean
    public Queue fanoutQueueB() {
        return new Queue(FANOUT_QUEUE_B, true);
    }

    @Bean
    public Queue fanoutQueueC() {
        return new Queue(FANOUT_QUEUE_C, true);
    }

    // ========================================================================
    // BINDINGS: Connect queues to exchange
    // ========================================================================

    @Bean
    public Binding bindingQueueA(Queue fanoutQueueA, FanoutExchange fanoutExchange) {
        return BindingBuilder.bind(fanoutQueueA).to(fanoutExchange);
    }

    @Bean
    public Binding bindingQueueB(Queue fanoutQueueB, FanoutExchange fanoutExchange) {
        return BindingBuilder.bind(fanoutQueueB).to(fanoutExchange);
    }

    @Bean
    public Binding bindingQueueC(Queue fanoutQueueC, FanoutExchange fanoutExchange) {
        return BindingBuilder.bind(fanoutQueueC).to(fanoutExchange);
    }

    // ========================================================================
    // MESSAGE CONVERTER: JSON serialization
    // ========================================================================

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ========================================================================
    // RABBIT TEMPLATE: For publishing messages
    // ========================================================================

    /**
     * RabbitTemplate with observation enabled for automatic trace propagation.
     *
     * HOW IT WORKS:
     * 1. Before sending message, Micrometer injects trace headers:
     *    - X-B3-TraceId: c5086f3bafafaedc915172729428f3d8
     *    - X-B3-SpanId: 2c4MU7ghXjA
     *    - X-B3-ParentSpanId: ...
     * 2. Creates a "send" span in the trace
     * 3. Consumer receives headers and continues the trace
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());

        // ⭐ CRITICAL: Enables automatic trace context injection
        template.setObservationEnabled(true);

        return template;
    }

    // ========================================================================
    // LISTENER CONTAINER FACTORY: For consuming messages
    // ========================================================================

    /**
     * RabbitListener container factory with observation enabled.
     *
     * HOW IT WORKS:
     * 1. Receives message with trace headers
     * 2. Extracts trace context (traceId, spanId)
     * 3. Creates child span for processing
     * 4. Sets MDC for logging (traceId, spanId)
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        // ⭐ CRITICAL: Enables automatic trace context extraction
        factory.setObservationEnabled(true);
        factory.setMessageConverter(messageConverter());

        return factory;
    }
}
```

### Step 2: Create Publisher (Gateway Service)

Update `src/main/java/dev/siegfred/tel/controller/GreetController.java`:

```java
package dev.siegfred.tel.controller;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class GreetController {

    private static final Logger log = LoggerFactory.getLogger(GreetController.class);

    @Autowired
    private Tracer tracer;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    /**
     * Chain pattern: Publishes message to single queue
     */
    @GetMapping("/api/chain")
    public Map<String, Object> chain(
            @RequestParam(required = false, defaultValue = "test") String data) {

        log.info("Chain request received: {}", data);

        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("request.type", "rabbitmq-chain");
            currentSpan.tag("request.data", data);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("data", data);
        message.put("source", "gateway");
        message.put("timestamp", System.currentTimeMillis());

        // Publish to chain queue
        // Trace context automatically injected by RabbitTemplate!
        rabbitTemplate.convertAndSend("tel.chain.queue", message);

        log.info("Message sent to chain queue with traceId: {}",
                currentSpan != null ? currentSpan.context().traceId() : "N/A");

        Map<String, Object> response = new HashMap<>();
        response.put("status", "Message sent to chain queue");
        response.put("data", data);
        return response;
    }

    /**
     * Fan-out pattern: Publishes message to fanout exchange
     * Message is delivered to ALL bound queues simultaneously
     */
    @GetMapping("/api/fanout")
    public Map<String, Object> fanout(
            @RequestParam(required = false, defaultValue = "test") String data) {

        log.info("Fan-out request received: {}", data);

        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("request.type", "rabbitmq-fanout");
            currentSpan.tag("messaging.pattern", "fan-out");
            currentSpan.tag("messaging.exchange", "tel.fanout.exchange");
            currentSpan.tag("request.data", data);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("data", data);
        message.put("source", "gateway");
        message.put("timestamp", System.currentTimeMillis());

        // Publish to fanout exchange (empty routing key = broadcast to all queues)
        // Trace context automatically injected!
        rabbitTemplate.convertAndSend("tel.fanout.exchange", "", message);

        log.info("Message sent to fanout exchange with traceId: {}",
                currentSpan != null ? currentSpan.context().traceId() : "N/A");

        Map<String, Object> response = new HashMap<>();
        response.put("status", "Message sent to fanout exchange");
        response.put("data", data);
        return response;
    }
}
```

### Step 3: Create Consumer (Service-E)

Create `src/main/java/dev/siegfred/tel/consumer/MessageConsumer.java`:

```java
package dev.siegfred.tel.consumer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    @Autowired
    private Tracer tracer;

    /**
     * Chain pattern consumer
     */
    @RabbitListener(queues = "tel.chain.queue")
    public void receiveChainMessage(
            Map<String, Object> message,
            @Headers Map<String, Object> headers) {

        Span currentSpan = tracer.currentSpan();

        log.info("Received chain message: {}, traceId: {}",
                message,
                currentSpan != null ? currentSpan.context().traceId() : "N/A");

        if (currentSpan != null) {
            currentSpan.tag("message.data", message.get("data").toString());
            currentSpan.tag("message.source", message.get("source").toString());
            currentSpan.tag("messaging.destination.name", "tel.chain.queue");
        }

        // Simulate processing
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Chain message processed");
    }

    /**
     * Fan-out pattern consumers - all receive the SAME message in parallel
     */

    @RabbitListener(queues = "tel.fanout.queue.a")
    public void receiveFanoutMessageA(
            Map<String, Object> message,
            @Headers Map<String, Object> headers) {
        processFanoutMessage(message, "tel.fanout.queue.a", "Consumer-A");
    }

    @RabbitListener(queues = "tel.fanout.queue.b")
    public void receiveFanoutMessageB(
            Map<String, Object> message,
            @Headers Map<String, Object> headers) {
        processFanoutMessage(message, "tel.fanout.queue.b", "Consumer-B");
    }

    @RabbitListener(queues = "tel.fanout.queue.c")
    public void receiveFanoutMessageC(
            Map<String, Object> message,
            @Headers Map<String, Object> headers) {
        processFanoutMessage(message, "tel.fanout.queue.c", "Consumer-C");
    }

    private void processFanoutMessage(
            Map<String, Object> message,
            String queueName,
            String consumerName) {

        Span currentSpan = tracer.currentSpan();

        log.info("{} received message from {}: {}, traceId: {}",
                consumerName, queueName, message,
                currentSpan != null ? currentSpan.context().traceId() : "N/A");

        if (currentSpan != null) {
            currentSpan.tag("consumer.name", consumerName);
            currentSpan.tag("message.data", message.get("data").toString());
            currentSpan.tag("messaging.pattern", "fan-out");
            currentSpan.tag("messaging.destination.name", queueName);
        }

        // Simulate processing
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("{} processed message", consumerName);
    }
}
```

### Step 4: How Trace Propagation Works

**1. Message Publishing (Gateway Service)**

```java
// Current trace context
TraceID: c5086f3bafafaedc915172729428f3d8
SpanID: 2c4MU7ghXjA

// RabbitTemplate automatically injects headers:
rabbitTemplate.convertAndSend("tel.chain.queue", message);

// Message headers (B3 propagation format):
{
  "X-B3-TraceId": "c5086f3bafafaedc915172729428f3d8",
  "X-B3-SpanId": "XbINWsBOoVY",
  "X-B3-ParentSpanId": "2c4MU7ghXjA",
  "X-B3-Sampled": "1"
}
```

**2. Message Consumption (Service-E)**

```java
// RabbitListener extracts headers automatically
@RabbitListener(queues = "tel.chain.queue")
public void receive(Map<String, Object> message) {
    // Trace context is restored!
    // Same TraceID, new SpanID (child span)

    Span currentSpan = tracer.currentSpan();
    // TraceID: c5086f3bafafaedc915172729428f3d8 (SAME!)
    // SpanID: kAbLYVTQJVU (NEW - child span)
    // ParentSpanID: XbINWsBOoVY (from headers)
}
```

**3. Trace Timeline Visualization**

```
Time ──────────────────────────────────────────────────────────────▶

Gateway: http get /api/chain
├─ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
│  SpanID: 2c4MU7ghXjA
│
└──▶ RabbitMQ: tel.chain.queue send
    ├─ ━━━━━━━━━━━━━━━━━━━━━
    │  SpanID: XbINWsBOoVY
    │  ParentSpanID: 2c4MU7ghXjA
    │
    └──▶ Service-E: tel.chain.queue receive
        ├─ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        │  SpanID: kAbLYVTQJVU
        │  ParentSpanID: XbINWsBOoVY
        │
        └──▶ Processing...

TraceID: c5086f3bafafaedc915172729428f3d8 (SAME across all spans!)
```

---

## Part 5: Custom Metadata

### Resource Attributes vs Span Attributes

```
┌─────────────────────────────────────────────────────────────────┐
│                         TRACE                                    │
│  TraceID: c5086f3bafafaedc915172729428f3d8                       │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  SERVICE: gateway                                        │    │
│  │                                                           │    │
│  │  RESOURCE ATTRIBUTES (Apply to ALL spans in service):    │    │
│  │  ├─ service.name: gateway                                │    │
│  │  ├─ service.version: 1.0.0                               │    │
│  │  ├─ created.by: J                                        │    │
│  │  └─ built.with: Java                                     │    │
│  │                                                           │    │
│  │  ┌──────────────────────────────────────────────┐        │    │
│  │  │  SPAN 1: http get /api/chain                 │        │    │
│  │  │  SpanID: 2c4MU7ghXjA                         │        │    │
│  │  │                                               │        │    │
│  │  │  SPAN ATTRIBUTES (Specific to this span):    │        │    │
│  │  │  ├─ http.method: GET                         │        │    │
│  │  │  ├─ http.url: /api/chain                     │        │    │
│  │  │  ├─ request.type: rabbitmq-chain             │        │    │
│  │  │  └─ request.data: test                       │        │    │
│  │  └──────────────────────────────────────────────┘        │    │
│  │                                                           │    │
│  │  ┌──────────────────────────────────────────────┐        │    │
│  │  │  SPAN 2: tel.chain.queue send                │        │    │
│  │  │  SpanID: XbINWsBOoVY                         │        │    │
│  │  │  ParentSpanID: 2c4MU7ghXjA                   │        │    │
│  │  │                                               │        │    │
│  │  │  SPAN ATTRIBUTES:                            │        │    │
│  │  │  ├─ messaging.destination.name: tel.chain.q  │        │    │
│  │  │  ├─ messaging.system: rabbitmq               │        │    │
│  │  │  └─ peer.service: RabbitMQ                   │        │    │
│  │  └──────────────────────────────────────────────┘        │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  SERVICE: service-e                                      │    │
│  │                                                           │    │
│  │  RESOURCE ATTRIBUTES:                                    │    │
│  │  ├─ service.name: service-e                              │    │
│  │  ├─ service.version: 1.0.0                               │    │
│  │  ├─ created.by: J                                        │    │
│  │  └─ built.with: Java                                     │    │
│  │                                                           │    │
│  │  ┌──────────────────────────────────────────────┐        │    │
│  │  │  SPAN 3: tel.chain.queue receive             │        │    │
│  │  │  SpanID: kAbLYVTQJVU                         │        │    │
│  │  │  ParentSpanID: XbINWsBOoVY                   │        │    │
│  │  │                                               │        │    │
│  │  │  SPAN ATTRIBUTES:                            │        │    │
│  │  │  ├─ message.data: test                       │        │    │
│  │  │  ├─ message.source: gateway                  │        │    │
│  │  │  └─ messaging.destination.name: tel.chain.q  │        │    │
│  │  └──────────────────────────────────────────────┘        │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### Summary Table

| Attribute Type | Scope | Set Once | Use Case | Example |
|----------------|-------|----------|----------|---------|
| **Resource Attributes** | Service-wide | Yes (at startup) | Service identity, environment, version | `created.by=J`, `built.with=Java`, `service.version=1.0.0` |
| **Span Attributes** | Per-operation | No (per span) | Operation-specific data, parameters, results | `http.method=GET`, `request.data=test`, `consumer.name=Consumer-A` |

---

## Real Trace Examples

### Example 1: Chain Pattern Trace

**Request:**
```bash
curl "http://localhost:8080/api/chain?data=debug-test-1"
```

**Complete Trace JSON (Simplified):**
```json
{
  "batches": [
    {
      "resource": {
        "attributes": [
          { "key": "service.name", "value": { "stringValue": "gateway" } },
          { "key": "service.version", "value": { "stringValue": "1.0.0" } },
          { "key": "created.by", "value": { "stringValue": "J" } },
          { "key": "built.with", "value": { "stringValue": "Java" } }
        ]
      },
      "scopeSpans": [
        {
          "spans": [
            {
              "traceId": "c5086f3bafafaedc915172729428f3d8",
              "spanId": "2c4MU7ghXjA",
              "name": "http get /api/chain",
              "kind": "SPAN_KIND_SERVER",
              "startTimeUnixNano": "1760882364261000000",
              "endTimeUnixNano": "1760882364267000000",
              "attributes": [
                { "key": "http.method", "value": { "stringValue": "GET" } },
                { "key": "http.url", "value": { "stringValue": "/api/chain" } },
                { "key": "request.type", "value": { "stringValue": "rabbitmq-chain" } },
                { "key": "request.data", "value": { "stringValue": "debug-test-1" } },
                { "key": "http.status_code", "value": { "intValue": "200" } }
              ]
            },
            {
              "traceId": "c5086f3bafafaedc915172729428f3d8",
              "spanId": "XbINWsBOoVY",
              "parentSpanId": "2c4MU7ghXjA",
              "name": "tel.chain.queue send",
              "kind": "SPAN_KIND_PRODUCER",
              "startTimeUnixNano": "1760882364264000000",
              "endTimeUnixNano": "1760882364266000000",
              "attributes": [
                { "key": "messaging.destination.name", "value": { "stringValue": "tel.chain.queue" } },
                { "key": "messaging.system", "value": { "stringValue": "rabbitmq" } },
                { "key": "peer.service", "value": { "stringValue": "RabbitMQ" } }
              ]
            }
          ]
        }
      ]
    },
    {
      "resource": {
        "attributes": [
          { "key": "service.name", "value": { "stringValue": "service-e" } },
          { "key": "service.version", "value": { "stringValue": "1.0.0" } },
          { "key": "created.by", "value": { "stringValue": "J" } },
          { "key": "built.with", "value": { "stringValue": "Java" } }
        ]
      },
      "scopeSpans": [
        {
          "spans": [
            {
              "traceId": "c5086f3bafafaedc915172729428f3d8",
              "spanId": "kAbLYVTQJVU",
              "parentSpanId": "XbINWsBOoVY",
              "name": "tel.chain.queue receive",
              "kind": "SPAN_KIND_CONSUMER",
              "startTimeUnixNano": "1760882364283000000",
              "endTimeUnixNano": "1760882364337000000",
              "attributes": [
                { "key": "message.data", "value": { "stringValue": "debug-test-1" } },
                { "key": "message.source", "value": { "stringValue": "gateway" } },
                { "key": "messaging.destination.name", "value": { "stringValue": "tel.chain.queue" } },
                { "key": "messaging.system", "value": { "stringValue": "rabbitmq" } }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

**Visual Timeline:**
```
Gateway Service (service.name=gateway)
│
├─ http get /api/chain [200ms]
│  ├─ Duration: 6ms
│  ├─ Attributes:
│  │  ├─ http.method: GET
│  │  ├─ request.type: rabbitmq-chain
│  │  └─ request.data: debug-test-1
│  │
│  └─ tel.chain.queue send [2ms]
│     ├─ messaging.system: rabbitmq
│     └─ messaging.destination.name: tel.chain.queue
│
└─ [Message travels through RabbitMQ with trace headers]

Service-E (service.name=service-e)
│
└─ tel.chain.queue receive [54ms]
   ├─ Duration: 54ms
   ├─ Attributes:
   │  ├─ message.data: debug-test-1
   │  ├─ message.source: gateway
   │  └─ messaging.system: rabbitmq
   │
   └─ [Processing complete]

TraceID: c5086f3bafafaedc915172729428f3d8 (SAME!)
```

### Example 2: Fan-Out Pattern Trace

**Request:**
```bash
curl "http://localhost:8080/api/fanout?data=demo-fanout-1"
```

**Complete Trace JSON (Simplified):**
```json
{
  "batches": [
    {
      "resource": {
        "attributes": [
          { "key": "service.name", "value": { "stringValue": "gateway" } },
          { "key": "created.by", "value": { "stringValue": "J" } },
          { "key": "built.with", "value": { "stringValue": "Java" } }
        ]
      },
      "scopeSpans": [
        {
          "spans": [
            {
              "traceId": "214dd5cbc438819c8956e27f4617bf1b",
              "spanId": "2c4MU7ghXjA",
              "name": "http get /api/fanout",
              "kind": "SPAN_KIND_SERVER",
              "attributes": [
                { "key": "messaging.pattern", "value": { "stringValue": "fan-out" } },
                { "key": "messaging.exchange", "value": { "stringValue": "tel.fanout.exchange" } },
                { "key": "fan-out.queues", "value": { "stringValue": "tel.fanout.queue.a,tel.fanout.queue.b,tel.fanout.queue.c" } }
              ]
            },
            {
              "traceId": "214dd5cbc438819c8956e27f4617bf1b",
              "spanId": "XbINWsBOoVY",
              "parentSpanId": "2c4MU7ghXjA",
              "name": "tel.fanout.exchange/ send",
              "kind": "SPAN_KIND_PRODUCER"
            }
          ]
        }
      ]
    },
    {
      "resource": {
        "attributes": [
          { "key": "service.name", "value": { "stringValue": "service-e" } },
          { "key": "created.by", "value": { "stringValue": "J" } },
          { "key": "built.with", "value": { "stringValue": "Java" } }
        ]
      },
      "scopeSpans": [
        {
          "spans": [
            {
              "traceId": "214dd5cbc438819c8956e27f4617bf1b",
              "spanId": "kAbLYVTQJVU",
              "parentSpanId": "XbINWsBOoVY",
              "name": "tel.fanout.queue.a receive",
              "kind": "SPAN_KIND_CONSUMER",
              "attributes": [
                { "key": "consumer.name", "value": { "stringValue": "Consumer-A" } },
                { "key": "messaging.pattern", "value": { "stringValue": "fan-out" } },
                { "key": "messaging.consumer.type", "value": { "stringValue": "parallel" } }
              ]
            },
            {
              "traceId": "214dd5cbc438819c8956e27f4617bf1b",
              "spanId": "58vr6C6Mt9k",
              "parentSpanId": "XbINWsBOoVY",
              "name": "tel.fanout.queue.b receive",
              "kind": "SPAN_KIND_CONSUMER",
              "attributes": [
                { "key": "consumer.name", "value": { "stringValue": "Consumer-B" } },
                { "key": "messaging.pattern", "value": { "stringValue": "fan-out" } }
              ]
            },
            {
              "traceId": "214dd5cbc438819c8956e27f4617bf1b",
              "spanId": "6KPQhL/0xYw",
              "parentSpanId": "XbINWsBOoVY",
              "name": "tel.fanout.queue.c receive",
              "kind": "SPAN_KIND_CONSUMER",
              "attributes": [
                { "key": "consumer.name", "value": { "stringValue": "Consumer-C" } },
                { "key": "messaging.pattern", "value": { "stringValue": "fan-out" } }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

**Visual Timeline (Parallel Execution):**
```
Gateway Service
│
├─ http get /api/fanout [2ms]
│  └─ tel.fanout.exchange/ send [0.3ms]
│     └─ Broadcasts to 3 queues
│
└─ [RabbitMQ distributes to all bound queues]

Service-E (All 3 consumers process SIMULTANEOUSLY)
│
├─ Consumer-A: tel.fanout.queue.a receive [104ms] ─────────┐
│  ├─ consumer.name: Consumer-A                            │
│  └─ messaging.pattern: fan-out                           │
│                                                           │
├─ Consumer-B: tel.fanout.queue.b receive [104ms] ─────────┤ PARALLEL!
│  ├─ consumer.name: Consumer-B                            │
│  └─ messaging.pattern: fan-out                           │
│                                                           │
└─ Consumer-C: tel.fanout.queue.c receive [104ms] ─────────┘
   ├─ consumer.name: Consumer-C
   └─ messaging.pattern: fan-out

TraceID: 214dd5cbc438819c8956e27f4617bf1b (SAME!)
Total Duration: ~104ms (parallel execution, not sequential!)
```

**Key Observations:**
1. **Same TraceID** across gateway and all 3 consumers
2. **Same ParentSpanID** for all 3 consumer spans (XbINWsBOoVY = send span)
3. **Parallel execution**: All 3 consumers start at ~same time
4. **Different SpanIDs**: Each consumer gets unique span ID
5. **Custom attributes**: `consumer.name` differentiates the consumers

---

## Testing & Verification

### Step 1: Start Everything

```bash
# Start infrastructure
docker-compose up -d

# Build application
./gradlew clean build

# Run application (or multiple instances for microservices)
java -jar build/libs/tel-0.0.1-SNAPSHOT.jar
```

### Step 2: Generate Test Traffic

```bash
# Chain pattern
for i in {1..5}; do
  curl "http://localhost:8080/api/chain?data=test-$i"
  sleep 1
done

# Fan-out pattern
for i in {1..5}; do
  curl "http://localhost:8080/api/fanout?data=test-$i"
  sleep 1
done
```

### Step 3: Search Traces in Grafana

Open http://localhost:3000 → Explore → Tempo

**Search by Service:**
```
{service.name="gateway"}
```

**Search by Custom Metadata:**
```
{created.by="J"}
{built.with="Java"}
```

**Search by Pattern:**
```
{messaging.pattern="fan-out"}
```

### Step 4: Verify Trace Propagation

**Check Trace Details:**
1. Click on any trace
2. Expand service graph → See all services involved
3. Check Resource Attributes section:
   - ✅ `created.by: J`
   - ✅ `built.with: Java`
   - ✅ `service.version: 1.0.0`

**Verify Fan-Out Pattern:**
1. Search for fan-out traces
2. Verify you see 3 parallel consumer spans
3. All should have same parent span ID
4. Different consumer.name attributes

### Step 5: Query Metrics

Open http://localhost:9090

```promql
# Request rate
rate(http_server_requests_seconds_count[5m])

# Error rate
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# P95 latency
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# Custom metrics
app_greet_requests_total
```

### Step 6: Query Logs

Grafana → Explore → Loki

```logql
# All logs from gateway
{service_name="gateway"}

# Logs for specific trace
{service_name="gateway"} |= "c5086f3bafafaedc915172729428f3d8"

# Error logs
{service_name="gateway"} |= "ERROR"
```

---

## Troubleshooting

### Traces Not Appearing

```bash
# Check OTEL Collector is receiving traces
docker logs otel-collector | grep "traces"

# Check Tempo is accessible
curl http://localhost:3200/ready

# Check application is sending traces
docker logs <your-app> | grep traceId

# Verify environment variables
echo $OTEL_EXPORTER_OTLP_ENDPOINT
```

### RabbitMQ Trace Propagation Not Working

```bash
# Verify observation is enabled
# In application logs, look for:
# "ObservationRegistry configured"

# Check RabbitMQ headers
# Enable RabbitMQ management plugin and check message headers:
# Should see X-B3-TraceId, X-B3-SpanId

# Verify RabbitTemplate configuration
# setObservationEnabled(true) must be called
```

### Metrics Not Exporting

```bash
# Check Prometheus endpoint
curl http://localhost:8080/actuator/prometheus

# Check OTLP metrics endpoint
curl http://localhost:4318/v1/metrics

# Verify management.metrics.export.otlp.enabled=true
```

---

## Complete Working Example

All code from this guide is available in this repository. To run:

```bash
# 1. Clone and navigate
cd tel

# 2. Start infrastructure
docker-compose -f docker-compose-microservices.yml up -d

# 3. Build and start services
./start.sh

# 4. Generate sample traces
./demo.sh

# 5. Open Grafana
open http://localhost:3000
```

---

## Summary

### What We Built

✅ **Traces**: Distributed tracing across HTTP and RabbitMQ
✅ **Metrics**: JVM, HTTP, custom business metrics
✅ **Logs**: Structured logging with trace context
✅ **Custom Metadata**: Resource attributes for filtering
✅ **RabbitMQ Propagation**: Automatic trace context injection
✅ **Fan-Out Pattern**: Parallel message consumption with shared trace

### Key Technologies

- **Spring Boot 3.5.6**: Auto-configuration for OpenTelemetry
- **Micrometer Tracing**: Abstraction layer for tracing
- **OpenTelemetry**: Vendor-neutral observability standard
- **Grafana Stack**: Tempo, Loki, Prometheus for visualization
- **RabbitMQ**: Message broker with automatic trace propagation

### Key Takeaways

1. **Zero-code instrumentation**: Spring Boot auto-configures most tracing
2. **Automatic propagation**: HTTP and RabbitMQ traces flow automatically
3. **Resource vs Span attributes**: Service-wide vs operation-specific
4. **OTLP everywhere**: Single protocol for traces, metrics, logs
5. **Observable by default**: Just add dependencies and configure endpoints

---

## Next Steps

1. **Add more services**: Extend the chain pattern
2. **Custom spans**: Use `@NewSpan` annotation for method tracing
3. **Correlate logs**: Link logs to traces in Grafana
4. **Alerting**: Set up Prometheus alerts for error rates
5. **Performance tuning**: Adjust sampling rates for production

---

**Built with ❤️ by J using Java**

*OpenTelemetry Version: 1.x*
*Last Updated: 2025-10-19*
