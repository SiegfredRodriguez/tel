# OpenTelemetry Setup Guide - Spring Boot Application

This document describes how this Spring Boot application was instrumented with OpenTelemetry for complete observability (traces, logs, and metrics).

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Step 1: Project Setup](#step-1-project-setup)
4. [Step 2: Traces Configuration](#step-2-traces-configuration)
5. [Step 3: Logs Configuration](#step-3-logs-configuration)
6. [Step 4: Metrics Configuration](#step-4-metrics-configuration)
7. [Step 5: Observability Stack Setup](#step-5-observability-stack-setup)
8. [Step 6: Grafana Configuration](#step-6-grafana-configuration)
9. [Verification](#verification)
10. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

```
┌─────────────────┐
│  Spring Boot    │
│   Application   │
└────────┬────────┘
         │
         ├─────────────────────────────────────────┐
         │                                         │
         │ Traces (OTLP HTTP)                      │ Logs (JSON File)
         │                                         │
         ▼                                         ▼
┌────────────────────┐                    ┌──────────────┐
│  OTel Collector    │                    │   Promtail   │
│  (Port 4318/4317)  │                    │              │
└─────────┬──────────┘                    └──────┬───────┘
          │                                       │
          ├───────────┬────────────┐             │
          │           │            │             │
    Traces│     Metrics│           │             │
          │           │            │             │
          ▼           ▼            │             ▼
    ┌─────────┐  ┌─────────┐      │        ┌────────┐
    │  Tempo  │  │Prometheus│      │        │  Loki  │
    └────┬────┘  └────┬─────┘      │        └───┬────┘
         │            │             │            │
         └────────────┴─────────────┴────────────┘
                      │
                      ▼
              ┌──────────────┐
              │   Grafana    │
              │  (Port 3000) │
              └──────────────┘
```

### Signal Flows

- **Traces**: Spring Boot → OTLP HTTP → OTel Collector → Tempo
- **Logs**: Spring Boot → JSON File → Promtail → Loki
- **Metrics**: Spring Boot → OTLP HTTP → OTel Collector → Prometheus (remote write)

---

## Prerequisites

- Java 17+
- Gradle 8.x
- Docker & Docker Compose
- Spring Boot 3.5.6

---

## Step 1: Project Setup

### 1.1 Add Dependencies to `build.gradle`

```gradle
dependencies {
    // Spring Boot starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // OpenTelemetry tracing
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'

    // Metrics exporters
    implementation 'io.micrometer:micrometer-registry-prometheus'  // For backwards compatibility
    implementation 'io.micrometer:micrometer-registry-otlp'        // OTLP metrics

    // Structured logging with trace context
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'

    // Lombok (optional)
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

**Why these dependencies?**
- `micrometer-tracing-bridge-otel`: Bridges Spring Boot's Micrometer to OpenTelemetry
- `opentelemetry-exporter-otlp`: Exports traces via OTLP protocol
- `grpc-netty`: Required for OTLP gRPC communication
- `micrometer-registry-otlp`: Exports metrics via OTLP
- `logstash-logback-encoder`: Formats logs as JSON with trace context

### 1.2 Create Sample Controller

```java
package dev.siegfred.tel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class GreetController {

    @GetMapping("/greet")
    public Map<String, Object> greet(@RequestParam(defaultValue = "World") String name) {
        log.info("Received greeting request for: {}", name);
        log.debug("Processing greeting for name: {}", name);

        String message = "hello";

        log.info("Returning greeting: {} to {}", message, name);

        return Map.of(
            "message", message,
            "name", name,
            "timestamp", System.currentTimeMillis()
        );
    }

    @GetMapping("/error")
    public Map<String, Object> error() {
        log.error("Error endpoint called - simulating error");
        throw new RuntimeException("Simulated error for testing");
    }
}
```

---

## Step 2: Traces Configuration

### 2.1 Configure `application.properties`

```properties
spring.application.name=tel
server.port=8080

# OpenTelemetry Tracing
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# Service resource attributes
otel.resource.attributes=service.name=tel,service.version=1.0.0,deployment.environment=development
```

**Configuration Breakdown:**
- `sampling.probability=1.0`: Sample 100% of traces (use lower values in production)
- `otlp.tracing.endpoint`: OTLP HTTP endpoint for the OTel Collector
- `otel.resource.attributes`: Semantic conventions for service identity

### 2.2 How Traces Work

Spring Boot's `micrometer-tracing-bridge-otel` automatically:
- Creates spans for HTTP requests
- Propagates trace context via HTTP headers (W3C Trace Context)
- Injects `traceId` and `spanId` into logs via MDC (Mapped Diagnostic Context)
- Exports traces to the configured OTLP endpoint

**Automatic Instrumentation:**
- `@RestController` endpoints → HTTP server spans
- Database calls → Database spans (if JDBC instrumented)
- HTTP client calls → HTTP client spans (if RestTemplate/WebClient instrumented)

---

## Step 3: Logs Configuration

### 3.1 Create `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Console appender for development -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [traceId=%X{traceId:-} spanId=%X{spanId:-}] - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- JSON file appender with trace context -->
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
```

### 3.2 Update `application.properties` for Log Format

```properties
# Logging - include trace context in MDC
logging.pattern.level=%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]
```

**How Trace Context Gets into Logs:**
1. Micrometer Tracing automatically adds `traceId` and `spanId` to MDC
2. Logback's `%X{traceId}` extracts from MDC
3. LogstashEncoder includes MDC keys in JSON output
4. Promtail extracts these as Loki labels

**Example JSON Log Entry:**
```json
{
  "@timestamp": "2025-10-18T10:46:20.437Z",
  "message": "Received greeting request for: CorrelationTest1",
  "logger_name": "dev.siegfred.tel.GreetController",
  "level": "INFO",
  "traceId": "319a8a36a80ac30052702857da7f1185",
  "spanId": "52702857da7f1185",
  "thread_name": "http-nio-8080-exec-1"
}
```

---

## Step 4: Metrics Configuration

### 4.1 Configure `application.properties` for Metrics

```properties
# OpenTelemetry Metrics - OTLP export
management.metrics.export.otlp.enabled=true
management.metrics.export.otlp.url=http://localhost:4318/v1/metrics
management.metrics.export.otlp.step=10s

# Keep Prometheus for backwards compatibility
management.metrics.export.prometheus.enabled=true
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.endpoints.web.exposure.include=health,prometheus,metrics

# Enable JVM and system metrics
management.metrics.enable.jvm=true
management.metrics.enable.process=true
management.metrics.enable.system=true
```

**Metrics Exported:**
- **HTTP Metrics**: Request count, duration, active requests
- **JVM Metrics**: Memory usage, GC pauses, threads, classes
- **System Metrics**: CPU usage, file descriptors, uptime

**Dual Mode Metrics:**
- **OTLP Push** (`job="tel"`): Pushed every 10s to OTel Collector
- **Prometheus Scrape** (`job="spring-boot-app"`): Scraped from `/actuator/prometheus`

---

## Step 5: Observability Stack Setup

### 5.1 Create `docker-compose.yml`

```yaml
services:
  # OpenTelemetry Collector - receives OTLP and forwards to backends
  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"   # OTLP gRPC receiver
      - "4318:4318"   # OTLP HTTP receiver
    depends_on:
      - tempo
      - prometheus

  # Tempo for traces
  tempo:
    image: grafana/tempo:latest
    command: [ "-config.file=/etc/tempo.yaml" ]
    volumes:
      - ./tempo-config.yaml:/etc/tempo.yaml
      - tempo-data:/var/tempo
    ports:
      - "3200:3200"   # tempo API

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
      - '--web.enable-remote-write-receiver'
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

### 5.2 Create `otel-collector-config.yaml`

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  batch:
    timeout: 10s
    send_batch_size: 1024

  resource:
    attributes:
      - key: service.name
        action: upsert
        from_attribute: service.name

exporters:
  # Forward traces to Tempo
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true

  # Forward metrics to Prometheus (via remote write)
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write
    tls:
      insecure: true

  # Debug exporter for troubleshooting
  debug:
    verbosity: basic

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch, resource]
      exporters: [otlp/tempo, debug]

    metrics:
      receivers: [otlp]
      processors: [batch, resource]
      exporters: [prometheusremotewrite, debug]
```

**Key Points:**
- `batch` processor: Batches telemetry for efficiency
- `resource` processor: Ensures service.name is preserved
- `debug` exporter: Logs telemetry to collector logs for troubleshooting

### 5.3 Create `tempo-config.yaml`

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
      path: /var/tempo/traces

query_frontend:
  search:
    enabled: true
```

### 5.4 Create `loki-config.yaml`

```yaml
auth_enabled: false

server:
  http_listen_port: 3100

ingester:
  lifecycler:
    address: 127.0.0.1
    ring:
      kvstore:
        store: inmemory
      replication_factor: 1
  chunk_idle_period: 5m
  chunk_retain_period: 30s

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

storage_config:
  tsdb_shipper:
    active_index_directory: /loki/index
    cache_location: /loki/cache
  filesystem:
    directory: /loki/chunks

limits_config:
  retention_period: 168h
  ingestion_rate_mb: 10
  ingestion_burst_size_mb: 20

table_manager:
  retention_deletes_enabled: true
  retention_period: 168h
```

### 5.5 Create `promtail-config.yaml`

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

**Critical Configuration:**
- `json` stage: Parses JSON log entries
- `labels` stage: Extracts `traceId` as a Loki label (enables trace-log correlation)
- `timestamp` stage: Uses log's timestamp (not ingestion time)

### 5.6 Create `prometheus.yml`

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

---

## Step 6: Grafana Configuration

### 6.1 Create `grafana-datasources.yaml`

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
        spanStartTimeShift: '-5m'
        spanEndTimeShift: '5m'
        tags:
          - key: service.name
            value: service_name
        queries:
          - name: 'Request Rate'
            query: 'rate(http_server_requests_seconds_count{job="tel"}[5m])'
          - name: 'Request Duration'
            query: 'rate(http_server_requests_seconds_sum{job="tel"}[5m]) / rate(http_server_requests_seconds_count{job="tel"}[5m])'
          - name: 'CPU Usage'
            query: 'process_cpu_usage{job="tel"}'
          - name: 'Memory Usage'
            query: 'jvm_memory_used_bytes{job="tel"}'
          - name: 'GC Rate'
            query: 'rate(jvm_gc_pause_seconds_count{job="tel"}[5m])'
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

**Correlation Configuration:**

1. **Trace → Logs (`tracesToLogsV2`)**:
   - Uses `traceId` label in Loki
   - Query: `{service_name="tel", traceId="${__trace.traceId}"}`
   - Shows logs from ±1 hour around the trace

2. **Trace → Metrics (`tracesToMetrics`)**:
   - Shows 5 pre-configured metric queries
   - Time range: ±5 minutes around the trace
   - Provides system context (CPU, memory, GC) at trace time

3. **Logs → Traces (`derivedFields`)**:
   - Extracts `traceId` from log text
   - Creates clickable link to trace view

---

## Verification

### 1. Start the Observability Stack

```bash
docker-compose up -d
```

**Check all containers are running:**
```bash
docker-compose ps
```

Expected output:
```
NAME                    STATUS
tel-grafana-1          Up
tel-loki-1             Up
tel-otel-collector-1   Up
tel-prometheus-1       Up
tel-promtail-1         Up
tel-tempo-1            Up
```

### 2. Start the Spring Boot Application

```bash
./gradlew bootRun
```

### 3. Generate Test Traffic

```bash
# Generate traces, logs, and metrics
for i in {1..10}; do
  curl "http://localhost:8080/api/greet?name=Test$i"
  sleep 1
done
```

### 4. Verify Traces in Tempo

**Via API:**
```bash
curl -s "http://localhost:3200/api/search?tags=service.name=tel" | jq '.traces[0].traceID'
```

**Via Grafana:**
1. Open http://localhost:3000
2. Go to Explore → Tempo
3. Search for `service.name=tel`
4. Click on a trace

### 5. Verify Logs in Loki

**Via API:**
```bash
curl -s 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="tel"}' \
  --data-urlencode 'start=1760784000000000000' \
  --data-urlencode 'end=1760785000000000000' \
  | jq '.data.result[0].values | length'
```

**Via Grafana:**
1. Go to Explore → Loki
2. Query: `{service_name="tel"}`
3. Verify `traceId` label is present

### 6. Verify Metrics in Prometheus

**Via API:**
```bash
# OTLP metrics
curl -s 'http://localhost:9090/api/v1/query?query=process_cpu_usage{job="tel"}' | jq '.data.result[0].value'

# Scraped metrics
curl -s 'http://localhost:9090/api/v1/query?query=process_cpu_usage{job="spring-boot-app"}' | jq '.data.result[0].value'
```

**Via Grafana:**
1. Go to Explore → Prometheus
2. Query: `process_cpu_usage{job="tel"}`

### 7. Verify Correlation

**Trace → Logs:**
1. Open a trace in Grafana
2. Click "Logs for this span" button
3. Should show 2+ log entries with matching `traceId`

**Trace → Metrics:**
1. Open a trace in Grafana
2. Click "Related metrics" dropdown
3. Select "CPU Usage" or "Memory Usage"
4. Should show metrics from the time of the trace

**Logs → Traces:**
1. Open logs in Grafana
2. Click on a log entry with a `traceId`
3. Click the trace ID link
4. Should open the trace view

### 8. Extract a Sample Trace ID

```bash
cat logs/application.log | jq -r 'select(.message | contains("Test")) | .traceId' | head -n 1
```

Use this trace ID to test correlation in Grafana.

---

## Troubleshooting

### No Traces Appearing

**Check OTel Collector logs:**
```bash
docker-compose logs otel-collector | grep -i trace
```

**Verify OTLP endpoint is reachable:**
```bash
curl -v http://localhost:4318/v1/traces
```

**Check application logs for errors:**
```bash
./gradlew bootRun | grep -i otel
```

### No Logs in Loki

**Check Promtail logs:**
```bash
docker-compose logs promtail | grep -i error
```

**Verify log file exists:**
```bash
ls -la logs/application.log
```

**Check Promtail can read the file:**
```bash
docker exec tel-promtail-1 cat /var/log/app/application.log | head
```

### No Metrics in Prometheus

**Check OTLP metrics are being sent:**
```bash
docker-compose logs otel-collector | grep -i metric
```

**Verify Prometheus remote write is enabled:**
```bash
docker-compose logs prometheus | grep "enable-remote-write-receiver"
```

**Check Prometheus scrape targets:**
```bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'
```

### Trace-Log Correlation Not Working

**Verify `traceId` is in logs:**
```bash
cat logs/application.log | jq '.traceId' | head -5
```

**Check Loki labels include `traceId`:**
```bash
curl -s 'http://localhost:3100/loki/api/v1/label/traceId/values' | jq '.data | length'
```

**Verify Grafana datasource query syntax:**
```bash
# Should use label selector, not text search
# Correct: {service_name="tel", traceId="abc123"}
# Wrong:   {service_name="tel"} |~ "abc123"
```

### Metrics Correlation Shows "No Data"

**Check if trace timestamp has metrics:**
1. Note trace timestamp (e.g., 2025-10-18 10:46:20)
2. Query Prometheus at that time:
```bash
curl -s 'http://localhost:9090/api/v1/query?query=process_cpu_usage{job="tel"}&time=1760785180'
```

**If empty:**
- Trace is too old (metrics retention expired)
- Generate fresh traces and use recent trace IDs

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `build.gradle` | Dependencies for OTel, Micrometer, and structured logging |
| `application.properties` | OTel endpoint configuration, metrics settings |
| `logback-spring.xml` | JSON logging with trace context |
| `docker-compose.yml` | Observability stack orchestration |
| `otel-collector-config.yaml` | OTel Collector pipelines and exporters |
| `tempo-config.yaml` | Tempo trace storage configuration |
| `loki-config.yaml` | Loki log storage and retention |
| `promtail-config.yaml` | Log collection and label extraction |
| `prometheus.yml` | Metrics scraping configuration |
| `grafana-datasources.yaml` | Datasources and correlation configuration |

---

## What Gets Correlated and How

### Trace ↔ Logs

**Mechanism**: `traceId` label in Loki
- Spring Boot MDC adds `traceId` to logs
- Promtail extracts `traceId` as Loki label
- Grafana queries Loki by `traceId`

**Query**: `{service_name="tel", traceId="<trace-id>"}`

**Correlation Type**: **Direct** - Each log entry has the exact trace ID

### Trace ↔ Metrics

**Mechanism**: Time-based correlation
- Grafana uses trace's timestamp
- Queries metrics at that time ±5 minutes
- Shows service-wide metrics (CPU, memory, GC)

**Query**: `process_cpu_usage{job="tel"}`

**Correlation Type**: **Indirect** - Shows system state at trace time, not per-trace metrics

**Note**: Spring Boot does not support exemplars (trace IDs in metrics) yet, so this is the best available correlation.

---

## OTLP vs Non-OTLP Metrics

### What OTLP Brings

✅ **Unified Protocol**: Same endpoint for traces & metrics
✅ **Push-based**: Works behind firewalls/NAT
✅ **Flexible Routing**: OTel Collector can send to multiple backends
✅ **Vendor Neutral**: Standard protocol, not tied to Prometheus

### Current Limitations (Spring Boot)

❌ **No Exemplars**: Can't link metrics → traces directly
❌ **Resource Attributes**: Not fully preserved through Prometheus remote write
❌ **No Better Correlation**: Same time-based correlation as Prometheus scraping

### Recommendation

**Keep Both Enabled** (OTLP + Prometheus Scraping):
- OTLP: Future-proofing, flexibility, observability standards
- Scraping: Rich labels, proven reliability, better for ad-hoc queries

---

## Next Steps

1. **Add Custom Spans**: Use `@NewSpan` or manual span creation for business logic
2. **Add Span Attributes**: Enrich traces with business context
3. **Create Grafana Dashboards**: Visualize metrics, logs, and traces together
4. **Set up Alerting**: Use Prometheus alerts for critical metrics
5. **Optimize Sampling**: Reduce to 10-20% in production
6. **Add Distributed Tracing**: Instrument HTTP clients for multi-service traces

---

## References

- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [Spring Boot Observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
- [Grafana Tempo](https://grafana.com/docs/tempo/latest/)
- [Grafana Loki](https://grafana.com/docs/loki/latest/)
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/)

---

**Document Version**: 1.0
**Last Updated**: 2025-10-18
**Author**: Claude Code Assistant
