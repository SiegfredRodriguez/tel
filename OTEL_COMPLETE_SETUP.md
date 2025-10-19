# Complete OpenTelemetry Setup Guide

This guide covers the complete setup of OpenTelemetry with Spring Boot, including traces, logs, and metrics with full correlation capabilities.

## Architecture Overview

```
┌─────────────────────────────────────────────┐
│         Spring Boot Application             │
│  • OTLP traces to Tempo (port 4318)        │
│  • JSON logs to file (with traceId)        │
│  • Prometheus metrics endpoint             │
└────┬─────────────────┬────────────────┬─────┘
     │                 │                │
     ↓                 ↓                ↓
┌─────────┐    ┌──────────────┐   ┌─────────┐
│  Tempo  │    │   Promtail   │   │Prometheus│
│ (traces)│    │  (log agent) │   │(metrics)│
└────┬────┘    └──────┬───────┘   └────┬────┘
     │                │                 │
     │         ┌──────▼──────┐          │
     │         │    Loki     │          │
     │         │   (logs)    │          │
     │         └──────┬──────┘          │
     │                │                 │
     └────────────┬───┴─────────────────┘
                  ↓
            ┌──────────┐
            │ Grafana  │
            │  (unified│
            │   view)  │
            └──────────┘
```

## Step 1: Add Dependencies

Update `build.gradle`:

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // OpenTelemetry for tracing
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'

    // Prometheus metrics
    implementation 'io.micrometer:micrometer-registry-prometheus'

    // JSON logging with trace context
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

## Step 2: Configure Application Properties

Create `src/main/resources/application.properties`:

```properties
spring.application.name=tel
server.port=8080

# OpenTelemetry Tracing
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# OpenTelemetry Metrics - enable Prometheus
management.metrics.export.prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus

# Service resource attributes
otel.resource.attributes=service.name=tel,service.version=1.0.0,deployment.environment=development

# Logging - include trace context in MDC
logging.pattern.level=%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]
```

**Key Points:**
- `management.tracing.sampling.probability=1.0` - 100% trace sampling (adjust for production)
- Trace endpoint points to Tempo's OTLP HTTP receiver
- Prometheus metrics exposed at `/actuator/prometheus`

## Step 3: Configure Logback for JSON Logging

Create `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- Console appender for local debugging -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [traceId=%X{traceId} spanId=%X{spanId}] - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- JSON file appender for Promtail to scrape -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <customFields>{"service_name":"tel"}</customFields>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

**Key Points:**
- Console logs include human-readable trace context
- File logs are in JSON format with `traceId` and `spanId` fields
- Logstash encoder automatically includes trace context from MDC

## Step 4: Create Application Controller

Create `src/main/java/dev/siegfred/tel/controller/GreetController.java`:

```java
package dev.siegfred.tel.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api")
public class GreetController {

    private static final Logger logger = LoggerFactory.getLogger(GreetController.class);
    private final Random random = new Random();

    @GetMapping("/greet")
    public Map<String, String> greet(@RequestParam(required = false, defaultValue = "World") String name) {
        logger.info("Received greet request for name: {}", name);

        // Simulate some processing time
        int processingTime = random.nextInt(100);
        logger.debug("Processing will take {} ms", processingTime);

        try {
            Thread.sleep(processingTime);
            logger.info("Successfully processed greeting for {}", name);
            return Map.of(
                "message", "hello",
                "name", name,
                "timestamp", String.valueOf(System.currentTimeMillis())
            );
        } catch (InterruptedException e) {
            logger.error("Error processing greeting request", e);
            Thread.currentThread().interrupt();
            return Map.of("error", "Processing interrupted");
        }
    }

    @GetMapping("/error")
    public Map<String, String> simulateError() {
        logger.warn("Error endpoint called - simulating error condition");

        try {
            logger.error("Simulating application error for demonstration");
            throw new RuntimeException("This is a simulated error for trace-log correlation demo");
        } catch (Exception e) {
            logger.error("Caught exception in error endpoint", e);
            throw e;
        }
    }
}
```

**Key Points:**
- No manual trace context propagation needed - Spring Boot auto-instrumentation handles it
- Logger statements automatically include trace context via MDC
- Multiple log statements per request demonstrate correlation

## Step 5: Configure Observability Backend

### 5.1 Tempo Configuration

Create `tempo-config.yaml`:

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

### 5.2 Loki Configuration

Create `loki-config.yaml`:

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  reject_old_samples: true
  reject_old_samples_max_age: 168h
  ingestion_rate_mb: 10
  ingestion_burst_size_mb: 20
```

### 5.3 Promtail Configuration

Create `promtail-config.yaml`:

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: spring-boot-app
    static_configs:
      - targets:
          - localhost
        labels:
          job: spring-boot-app
          service_name: tel
          __path__: /var/log/app/*.log

    pipeline_stages:
      # Parse JSON logs
      - json:
          expressions:
            timestamp: timestamp
            level: level
            logger: logger
            message: message
            traceId: traceId
            spanId: spanId
            thread: thread

      # Extract labels
      - labels:
          level:
          traceId:
          spanId:
          service_name:

      # Set timestamp
      - timestamp:
          source: timestamp
          format: RFC3339

      # Output the message
      - output:
          source: message
```

**Key Points:**
- Promtail scrapes JSON logs from `logs/application.log`
- Extracts `traceId` and `spanId` as Loki labels for correlation
- Labels enable querying logs by trace ID

### 5.4 Prometheus Configuration

Create `prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
        labels:
          application: 'tel'
          environment: 'development'
```

### 5.5 Grafana Datasources Configuration

Create `grafana-datasources.yaml`:

```yaml
apiVersion: 1

datasources:
  # Tempo for traces
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    uid: tempo
    isDefault: true
    editable: true
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        spanStartTimeShift: '-1h'
        spanEndTimeShift: '1h'
        filterByTraceID: true
        filterBySpanID: false
        tags:
          - key: traceId
            value: traceId
        customQuery: true
        query: '{service_name="tel", traceId="${__trace.traceId}"}'
      tracesToMetrics:
        datasourceUid: prometheus
      serviceMap:
        datasourceUid: prometheus
      lokiSearch:
        datasourceUid: loki

  # Loki for logs
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    uid: loki
    editable: true
    jsonData:
      derivedFields:
        - datasourceUid: tempo
          matcherRegex: "traceId=(\\w+)"
          name: TraceID
          url: '${__value.raw}'

  # Prometheus for metrics
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    uid: prometheus
    editable: true
    jsonData:
      exemplarTraceIdDestinations:
        - name: trace_id
          datasourceUid: tempo
```

**Key Points:**
- `tracesToLogsV2` configuration enables "Logs for this span" button in Grafana
- Custom query uses `traceId` label selector for accurate correlation
- `derivedFields` enables clicking trace IDs in logs to jump to traces

## Step 6: Docker Compose Setup

Create `docker-compose.yml`:

```yaml
services:
  # Tempo for traces
  tempo:
    image: grafana/tempo:latest
    command: [ "-config.file=/etc/tempo.yaml" ]
    volumes:
      - ./tempo-config.yaml:/etc/tempo.yaml
      - tempo-data:/var/tempo
    ports:
      - "3200:3200"   # tempo API
      - "4318:4318"   # OTLP HTTP receiver

  # Loki for logs
  loki:
    image: grafana/loki:latest
    command: [ "-config.file=/etc/loki/local-config.yaml" ]
    volumes:
      - ./loki-config.yaml:/etc/loki/local-config.yaml
      - loki-data:/loki
    ports:
      - "3100:3100"   # loki API

  # Promtail for log forwarding
  promtail:
    image: grafana/promtail:latest
    command: [ "-config.file=/etc/promtail/config.yml" ]
    volumes:
      - ./promtail-config.yaml:/etc/promtail/config.yml
      - ./logs:/var/log/app
    depends_on:
      - loki

  # Prometheus for metrics
  prometheus:
    image: prom/prometheus:latest
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/usr/share/prometheus/console_libraries'
      - '--web.console.templates=/usr/share/prometheus/consoles'
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    extra_hosts:
      - "host.docker.internal:host-gateway"

  # Grafana for visualization
  grafana:
    image: grafana/grafana:latest
    volumes:
      - ./grafana-datasources.yaml:/etc/grafana/provisioning/datasources/datasources.yaml
      - grafana-data:/var/lib/grafana
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
      - GF_AUTH_DISABLE_LOGIN_FORM=true
      - GF_FEATURE_TOGGLES_ENABLE=traceqlEditor,correlations
    ports:
      - "3000:3000"
    depends_on:
      - tempo
      - loki
      - prometheus

volumes:
  tempo-data:
  loki-data:
  prometheus-data:
  grafana-data:
```

## Step 7: Run the Stack

### 7.1 Create logs directory

```bash
mkdir -p logs
```

### 7.2 Start observability backend

```bash
docker-compose up -d
```

Verify all containers are running:

```bash
docker-compose ps
```

You should see:
- tempo
- loki
- promtail
- prometheus
- grafana

### 7.3 Start Spring Boot application

```bash
./gradlew bootRun
```

## Step 8: Generate Test Data

```bash
# Generate some requests
for i in {1..10}; do
  curl "http://localhost:8080/api/greet?name=User$i"
  sleep 1
done
```

## Step 9: Verify the Setup

### 9.1 Check Traces in Tempo

```bash
curl -s "http://localhost:3200/api/search?tags=service.name%3Dtel" | jq '.traces | length'
```

Should return a number > 0.

### 9.2 Check Logs in Loki

```bash
curl -s "http://localhost:3100/loki/api/v1/label/traceId/values" | jq '.data | length'
```

Should return a number > 0, showing trace IDs are in Loki.

### 9.3 Check Metrics in Prometheus

```bash
curl -s "http://localhost:8080/actuator/prometheus" | grep http_server_requests_seconds_count
```

Should show HTTP request metrics.

## Step 10: Use Grafana for Correlation

1. Open **http://localhost:3000** (no login required)
2. Navigate to **Explore** (compass icon on left sidebar)
3. Select **Tempo** datasource from dropdown
4. Query for traces: `{service.name="tel"}`
5. Click on any trace to view details
6. Click **"Logs for this span"** button on any span
7. Grafana switches to **Loki** and shows correlated logs with matching `traceId`

### Testing Trace-to-Logs Navigation

The correlation query used by Grafana:
```
{service_name="tel", traceId="<trace-id>"}
```

This queries Loki for all logs with the specific trace ID label.

### Testing Logs-to-Trace Navigation

1. In Grafana Explore, select **Loki** datasource
2. Query: `{service_name="tel"}`
3. Click on any log line
4. The `traceId` field is clickable
5. Clicking it jumps to the full trace in Tempo

## Understanding the Data Flow

### Traces
```
Spring Boot → OTLP HTTP (port 4318) → Tempo → Grafana
```

- Spring Boot auto-instruments HTTP requests
- Traces exported via OTLP protocol
- Tempo stores traces
- Grafana queries Tempo for trace visualization

### Logs
```
Spring Boot → JSON file → Promtail → Loki → Grafana
```

- Spring Boot writes JSON logs with `traceId` and `spanId`
- Promtail tails the log file
- Extracts `traceId` as a Loki label
- Loki stores logs with labels
- Grafana queries Loki by `traceId` for correlation

### Metrics
```
Spring Boot → /actuator/prometheus → Prometheus scrape → Grafana
```

- Spring Boot exposes metrics at `/actuator/prometheus`
- Prometheus scrapes metrics every 15s
- Grafana queries Prometheus for metric visualization

## Key Correlation Mechanism

**Automatic Trace Context Propagation:**

1. Micrometer Tracing Bridge automatically:
   - Creates trace and span IDs for each request
   - Stores them in MDC (Mapped Diagnostic Context)
   - Propagates them across thread boundaries

2. Logback Configuration:
   - LogstashEncoder reads `traceId` and `spanId` from MDC
   - Includes them in JSON log output
   - No manual code changes needed

3. Promtail Processing:
   - Parses JSON logs
   - Extracts `traceId` as a Loki label
   - Makes it queryable: `{traceId="..."}`

4. Grafana Correlation:
   - Uses `traceId` to link traces and logs
   - Query template: `{service_name="tel", traceId="${__trace.traceId}"}`
   - Provides seamless navigation between signals

## Troubleshooting

### No traces in Tempo

Check application logs for connection errors:
```bash
grep "Failed to export spans" logs/application.log
```

Verify Tempo is accepting connections:
```bash
docker-compose logs tempo | grep "listening"
```

### No logs in Loki

Check Promtail is tailing logs:
```bash
docker-compose logs promtail | grep "tail routine"
```

Verify log file exists:
```bash
ls -la logs/application.log
```

### Trace-Log correlation not working

Verify trace IDs are in Loki labels:
```bash
curl -s "http://localhost:3100/loki/api/v1/label/traceId/values" | jq '.'
```

Test correlation query manually:
```bash
TRACE_ID="<your-trace-id>"
curl -s "http://localhost:3100/loki/api/v1/query_range" \
  --data-urlencode "query={service_name=\"tel\", traceId=\"$TRACE_ID\"}" \
  --data-urlencode 'start=1760780000000000000' \
  --data-urlencode 'end=1760790000000000000' | jq '.data.result | length'
```

Should return > 0.

## Production Considerations

### 1. Trace Sampling

Change sampling rate in production:
```properties
management.tracing.sampling.probability=0.1  # 10% sampling
```

### 2. Log Retention

Configure Loki retention in `loki-config.yaml`:
```yaml
limits_config:
  retention_period: 744h  # 31 days
```

### 3. Storage

Replace local storage with object storage:
- Tempo: S3/GCS/Azure Blob
- Loki: S3/GCS/Azure Blob
- Prometheus: Remote storage

### 4. Security

- Enable authentication in Grafana
- Secure endpoints with TLS
- Use API keys for datasource access
- Implement network policies

### 5. Resource Limits

Set resource limits in docker-compose.yml:
```yaml
services:
  tempo:
    deploy:
      resources:
        limits:
          memory: 1G
          cpus: '0.5'
```

## Summary

You now have:
- ✅ Distributed tracing with OpenTelemetry and Tempo
- ✅ Structured logging with automatic trace context
- ✅ Prometheus metrics collection
- ✅ Unified visualization in Grafana
- ✅ Automatic trace-log-metric correlation
- ✅ Zero manual instrumentation required

The key to the correlation is:
1. Micrometer automatically propagates trace context to MDC
2. Logback encoder includes MDC values in JSON logs
3. Promtail extracts trace IDs as Loki labels
4. Grafana uses these labels to query across signals

All observability signals are now connected via `traceId`, enabling powerful debugging workflows from metrics → traces → logs and back.
