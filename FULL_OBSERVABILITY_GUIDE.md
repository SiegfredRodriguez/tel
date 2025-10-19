# Full OpenTelemetry Observability Stack

Complete guide for implementing **traces, logs, and metrics** with OpenTelemetry and Grafana.

## Overview

This setup demonstrates the **three pillars of observability** using OpenTelemetry:

```
┌──────────────────┐
│  Spring Boot App │
│                  │
│  • Traces  ──────┼────→ Tempo (port 4318)
│  • Logs    ──────┼────→ Loki (OTLP)
│  • Metrics ──────┼────→ Prometheus (scrape /actuator/prometheus)
└──────────────────┘
         │
         ↓
    ┌─────────┐
    │ Grafana │ ← Unified visualization with correlation
    └─────────┘
```

## Architecture Components

| Component | Purpose | Port | Protocol |
|-----------|---------|------|----------|
| **Tempo** | Trace storage | 4318 | OTLP HTTP |
| **Loki** | Log aggregation | 3100 | LogQL API |
| **Prometheus** | Metrics storage | 9090 | PromQL API |
| **Grafana** | Visualization | 3000 | HTTP |
| **Spring Boot** | Application | 8080 | HTTP |

---

## Part 1: Application Configuration

### 1.1 Dependencies (build.gradle)

```gradle
dependencies {
    // Web and actuator
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // OpenTelemetry tracing
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'

    // Prometheus metrics
    implementation 'io.micrometer:micrometer-registry-prometheus'

    // OpenTelemetry log appender
    implementation 'io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.10.0-alpha'
}
```

### 1.2 Application Properties

```properties
spring.application.name=tel
server.port=8080

# OpenTelemetry Tracing
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# Prometheus Metrics
management.metrics.export.prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus

# OpenTelemetry Logs
otel.logs.exporter=otlp
otel.exporter.otlp.logs.endpoint=http://localhost:4318
otel.exporter.otlp.logs.protocol=http/protobuf

# Service attributes
otel.resource.attributes=service.name=tel,service.version=1.0.0,deployment.environment=development
```

**Key Points:**
- Traces go to Tempo via OTLP HTTP
- Metrics exposed via Prometheus endpoint (scraped by Prometheus)
- Logs sent via OTLP (through Logback appender)

### 1.3 Logback Configuration (logback-spring.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- Console appender with trace context -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [trace_id=%X{trace_id} span_id=%X{span_id}] - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- OpenTelemetry OTLP appender -->
    <appender name="OTLP" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <captureExperimentalAttributes>true</captureExperimentalAttributes>
        <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTLP"/>
    </root>
</configuration>
```

**Important:** The pattern `%X{trace_id}` and `%X{span_id}` extract the trace context into console logs!

### 1.4 Controller with Logging

```java
@RestController
@RequestMapping("/api")
public class GreetController {
    private static final Logger logger = LoggerFactory.getLogger(GreetController.class);

    @GetMapping("/greet")
    public Map<String, String> greet(@RequestParam(required = false, defaultValue = "World") String name) {
        logger.info("Received greet request for name: {}", name);

        // Processing
        logger.info("Successfully processed greeting for {}", name);

        return Map.of(
            "message", "hello",
            "name", name,
            "timestamp", String.valueOf(System.currentTimeMillis())
        );
    }

    @GetMapping("/error")
    public Map<String, String> simulateError() {
        logger.warn("Error endpoint called");
        logger.error("Simulating application error");
        throw new RuntimeException("Demo error for trace-log correlation");
    }
}
```

**Automatic Correlation:** Logs automatically include `trace_id` and `span_id` from the active trace!

---

## Part 2: Backend Services Configuration

### 2.1 Tempo Configuration (tempo-config.yaml)

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

### 2.2 Loki Configuration (loki-config.yaml)

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

### 2.3 Prometheus Configuration (prometheus.yml)

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

**Note:** `host.docker.internal` allows Prometheus (in Docker) to reach the Spring Boot app on the host.

### 2.4 Docker Compose

```yaml
version: '3.8'

services:
  # Tempo for traces
  tempo:
    image: grafana/tempo:latest
    command: [ "-config.file=/etc/tempo.yaml" ]
    volumes:
      - ./tempo-config.yaml:/etc/tempo.yaml
      - tempo-data:/var/tempo
    ports:
      - "3200:3200"   # API
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP

  # Loki for logs
  loki:
    image: grafana/loki:latest
    command: [ "-config.file=/etc/loki/local-config.yaml" ]
    volumes:
      - ./loki-config.yaml:/etc/loki/local-config.yaml
      - loki-data:/loki
    ports:
      - "3100:3100"   # API

  # Prometheus for metrics
  prometheus:
    image: prom/prometheus:latest
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
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

**Feature Toggles:**
- `traceqlEditor`: Enable TraceQL query language
- `correlations`: Enable trace-to-log-to-metric correlations

---

## Part 3: Grafana Datasource Configuration with Correlations

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
          matcherRegex: "trace_id=(\\w+)"
          name: TraceID
          url: '$${__value.raw}'

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

### Correlation Configuration Explained

**Tempo → Logs:**
```yaml
tracesToLogsV2:
  datasourceUid: loki
  filterByTraceID: true
```
- Click "Logs for this span" in Tempo
- Automatically queries Loki for logs matching the trace ID

**Logs → Traces:**
```yaml
derivedFields:
  - matcherRegex: "trace_id=(\\w+)"
    datasourceUid: tempo
```
- Extracts `trace_id` from log lines
- Creates clickable link to view trace in Tempo

**Metrics → Traces:**
```yaml
exemplarTraceIdDestinations:
  - name: trace_id
    datasourceUid: tempo
```
- Prometheus exemplars link to traces
- Click metric point to see the trace that generated it

---

## Part 4: Running the Full Stack

### 4.1 Start Services

```bash
# 1. Start observability backend
docker-compose up -d

# 2. Wait for services to be ready
sleep 10

# 3. Start Spring Boot application
./gradlew bootRun
```

### 4.2 Verify Services

```bash
# Check Tempo
curl -s "http://localhost:3200/ready"

# Check Loki
curl -s "http://localhost:3100/ready"

# Check Prometheus
curl -s "http://localhost:9090/-/ready"

# Check Grafana
curl -s "http://localhost:3000/api/health"

# Check Spring Boot
curl "http://localhost:8080/actuator/health"
```

### 4.3 Generate Telemetry Data

```bash
# Generate traces, logs, and metrics
for i in {1..10}; do
  curl "http://localhost:8080/api/greet?name=User$i"
  sleep 1
done
```

### 4.4 Verify Data Collection

**Traces:**
```bash
curl -s "http://localhost:3200/api/search?tags=service.name%3Dtel" | jq '.traces | length'
```

**Metrics:**
```bash
curl -s "http://localhost:8080/actuator/prometheus" | grep http_server_requests
```

**Prometheus Scraping:**
```bash
curl -s "http://localhost:9090/api/v1/query?query=up{job='spring-boot-app'}" | jq '.data.result'
```

---

## Part 5: Trace-Log-Metric Correlation in Grafana

### 5.1 Access Grafana

Open http://localhost:3000 (no login required)

### 5.2 View Traces

1. Go to **Explore**
2. Select **Tempo** datasource
3. Query: `{ service.name="tel" }`
4. Click on any trace

**What you see:**
- Span timeline
- HTTP details (method, status, URL)
- Duration
- Trace ID and Span ID

### 5.3 Trace → Logs Correlation

**In the trace view:**

1. Click **"Logs for this span"** button
2. Grafana automatically switches to Loki
3. Shows all logs with matching `trace_id`

**Query executed automatically:**
```
{service_name="tel"} |= "trace_id=abc123..."
```

**What you see:**
- All log messages from that request
- Logger name
- Log level (INFO, ERROR, etc.)
- Message content
- **Same trace_id** linking them together!

### 5.4 Logs → Trace Correlation

**In Loki logs view:**

1. Find a log line with `trace_id`
2. Click the **trace_id** value (it's a link)
3. Grafana jumps to Tempo
4. Shows the full trace for that log

### 5.5 Metrics → Trace Correlation (with Exemplars)

**In Prometheus metrics view:**

1. Query: `rate(http_server_requests_seconds_count[5m])`
2. Look for exemplar markers (small dots on the graph)
3. Click an exemplar
4. Grafana shows the trace that contributed to that metric

---

## Part 6: Practical Demonstration

### Example 1: Debugging a Slow Request

**Scenario:** A request is slow

1. **Start in Metrics (Prometheus)**
   ```
   histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
   ```
   - See P95 latency spike

2. **Jump to Traces (Tempo)**
   - Click exemplar or search for slow traces
   - Filter by duration: `duration > 100ms`

3. **See span breakdown:**
   ```
   GET /api/greet: 150ms
     ├─ Database query: 120ms  ← Found the culprit!
     └─ Business logic: 30ms
   ```

4. **View Logs for Context:**
   - Click "Logs for this span"
   - See: `"Processing will take 120 ms"`
   - Log confirms database was slow

### Example 2: Investigating an Error

**Scenario:** 500 error reported

1. **Start in Logs (Loki)**
   ```
   {service_name="tel"} |= "ERROR"
   ```
   - Find: `"Simulating application error"`
   - See `trace_id=xyz789...`

2. **Click trace_id → Jump to Trace**
   - See full request flow
   - Span shows: `status=error`
   - Exception details in span attributes

3. **Check Metrics Impact:**
   - Look at error rate:
     ```
     rate(http_server_requests_seconds_count{status="500"}[5m])
     ```
   - Correlate error spike with trace timestamp

### Example 3: Understanding User Journey

**Scenario:** Track a specific user's requests

1. **Search Traces by Attribute:**
   ```
   { .http.url =~ ".*name=Alice.*" }
   ```

2. **View all traces for that user:**
   - See request pattern
   - Identify performance issues
   - Check error rates

3. **Correlate with Logs:**
   - Each trace links to logs
   - See complete application behavior
   - Debug user-specific issues

---

## Part 7: Console Output with Trace Context

When running the application, console logs show trace context:

```
2025-10-18 17:07:30.123 [http-nio-8080-exec-1] INFO  GreetController [trace_id=a1b2c3d4... span_id=e5f6g7h8...] - Received greet request for name: Alice
2025-10-18 17:07:30.145 [http-nio-8080-exec-1] INFO  GreetController [trace_id=a1b2c3d4... span_id=e5f6g7h8...] - Successfully processed greeting for Alice
```

**Key Elements:**
- `trace_id=a1b2c3d4...` - Links to distributed trace
- `span_id=e5f6g7h8...` - Identifies this specific operation
- Same IDs appear in Tempo, Loki, and Prometheus exemplars!

---

## Part 8: Architecture Benefits

### Unified Observability

| Signal | Purpose | When to Use |
|--------|---------|-------------|
| **Traces** | Request flow, latency | "Why is this request slow?" |
| **Logs** | Detailed events, errors | "What happened during this request?" |
| **Metrics** | Aggregated performance | "What's the overall system health?" |

### Correlation Enables

✅ **Root Cause Analysis**
- Start with high-level metrics
- Drill down to specific traces
- View detailed logs for context

✅ **Performance Debugging**
- See which spans are slow
- Check logs during slow periods
- Correlate with system metrics

✅ **Error Investigation**
- Find errors in logs
- Jump to trace to see full context
- Check if metrics show pattern

✅ **User Experience Monitoring**
- Track individual user requests (traces)
- See their complete journey
- Debug user-specific issues

---

## Summary

### What You've Built

1. **Full OTLP Integration**
   - Traces → Tempo
   - Logs → Loki (via OTLP Logback appender)
   - Metrics → Prometheus (scraped)

2. **Automatic Correlation**
   - Logs include trace_id & span_id
   - Grafana configured for bidirectional linking
   - Click between traces, logs, and metrics

3. **Zero Code Changes Required**
   - OpenTelemetry auto-instrumentation
   - Logback appender handles correlation
   - Spring Boot Actuator exposes metrics

4. **Production-Ready Stack**
   - Industry-standard backends
   - Grafana for unified visualization
   - Scalable architecture

### Key Takeaways

- **Trace ID** is the correlation key across all signals
- **Grafana datasource configuration** enables automatic linking
- **Logback OpenTelemetry appender** automatically adds trace context to logs
- **Prometheus exemplars** link metrics back to traces
- **All signals** flow through standard OTLP protocol

This is **true observability** - the ability to ask any question about your system and follow the data across traces, logs, and metrics to find the answer!
