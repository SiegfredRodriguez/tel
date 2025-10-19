# OpenTelemetry Implementation Manual
## A Step-by-Step Guide for Adding Full Observability to Any Spring Boot Application

**Target Audience**: Developers implementing OpenTelemetry for the first time
**Prerequisites**: Spring Boot 3.x, Java 17+, Docker
**Time Required**: 2-4 hours
**Result**: Complete observability with traces, logs, and metrics correlated in Grafana

---

## Table of Contents

1. [Overview](#overview)
2. [What You'll Build](#what-youll-build)
3. [Phase 1: Application Instrumentation](#phase-1-application-instrumentation)
4. [Phase 2: Observability Stack Setup](#phase-2-observability-stack-setup)
5. [Phase 3: Configuration & Correlation](#phase-3-configuration--correlation)
6. [Phase 4: Verification & Testing](#phase-4-verification--testing)
7. [Common Patterns & Decisions](#common-patterns--decisions)
8. [Troubleshooting Guide](#troubleshooting-guide)
9. [Production Considerations](#production-considerations)

---

## Overview

### What is OpenTelemetry?

OpenTelemetry (OTel) is an observability framework that provides:
- **Traces**: Request flow through your system
- **Metrics**: Performance and resource usage data
- **Logs**: Application events and errors

**Key Benefit**: All three signals are correlated, allowing you to jump from a slow trace to its logs and metrics instantly.

### Architecture You'll Build

```
┌─────────────────────┐
│  Your Spring Boot   │
│    Application      │
└──────────┬──────────┘
           │
    ┌──────┴──────┬────────────┐
    │             │            │
  Traces       Metrics      Logs
  (OTLP)       (OTLP)    (JSON File)
    │             │            │
    ▼             ▼            ▼
┌─────────────────────┐   ┌────────┐
│  OTel Collector     │   │Promtail│
└─────┬───────┬───────┘   └───┬────┘
      │       │               │
   Tempo  Prometheus      Loki
      │       │               │
      └───────┴───────────────┘
              │
         ┌────▼────┐
         │ Grafana │
         └─────────┘
```

---

## What You'll Build

### End Result

After completing this guide, you'll have:

1. ✅ **Automatic Trace Collection**
   - Every HTTP request creates a trace
   - Database calls show as spans (if configured)
   - HTTP client calls are traced

2. ✅ **Structured JSON Logs with Trace Context**
   - Every log includes `traceId` and `spanId`
   - Click a trace → see all its logs
   - Click a log → jump to its trace

3. ✅ **Metrics Export via OTLP**
   - HTTP request metrics (count, duration)
   - JVM metrics (memory, GC, threads)
   - System metrics (CPU, file descriptors)
   - Click a trace → see system metrics at that time

4. ✅ **Grafana Dashboards with Correlation**
   - Search traces in Tempo
   - Click "Logs for this span" → opens Loki logs
   - Click "Related metrics" → shows Prometheus graphs
   - Click traceId in logs → opens trace view

### Signal Details

| Signal | Protocol | Backend | Retention |
|--------|----------|---------|-----------|
| Traces | OTLP HTTP | Tempo | 7 days (configurable) |
| Logs | File → Promtail | Loki | 7 days (configurable) |
| Metrics | OTLP HTTP + Scrape | Prometheus | 15 days (default) |

---

## Phase 1: Application Instrumentation

### Step 1.1: Add Dependencies

**File**: `build.gradle` (or `pom.xml` for Maven)

```gradle
dependencies {
    // Required: Spring Boot starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Required: OpenTelemetry tracing
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
    implementation 'io.grpc:grpc-netty:1.68.1'

    // Required: OTLP metrics
    implementation 'io.micrometer:micrometer-registry-otlp'

    // Required: JSON logging with trace context
    implementation 'net.logstash.logback:logstash-logback-encoder:8.0'

    // Optional: Prometheus metrics (for backwards compatibility)
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

**Why each dependency?**
- `micrometer-tracing-bridge-otel`: Bridges Spring's Micrometer to OpenTelemetry
- `opentelemetry-exporter-otlp`: Exports traces via OTLP protocol
- `grpc-netty`: Required for OTLP communication
- `micrometer-registry-otlp`: Exports metrics via OTLP
- `logstash-logback-encoder`: Formats logs as JSON with trace context

**Action**: Add these to your `build.gradle` and run `./gradlew build` to download dependencies.

---

### Step 1.2: Configure Application Properties

**File**: `src/main/resources/application.properties`

```properties
# Application Identity
spring.application.name=your-service-name
server.port=8080

# ============================================
# TRACES Configuration
# ============================================
# Sample 100% of traces (reduce in production: 0.1 = 10%)
management.tracing.sampling.probability=1.0
# OTLP endpoint (will be OTel Collector)
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# ============================================
# METRICS Configuration
# ============================================
# Enable OTLP metrics export
management.metrics.export.otlp.enabled=true
management.metrics.export.otlp.url=http://localhost:4318/v1/metrics
management.metrics.export.otlp.step=10s

# Optional: Keep Prometheus scraping available
management.metrics.export.prometheus.enabled=true
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.endpoints.web.exposure.include=health,prometheus,metrics

# Enable JVM and system metrics
management.metrics.enable.jvm=true
management.metrics.enable.process=true
management.metrics.enable.system=true

# ============================================
# OpenTelemetry Resource Attributes
# ============================================
otel.resource.attributes=service.name=your-service-name,service.version=1.0.0,deployment.environment=development

# ============================================
# LOGGING Configuration
# ============================================
# Include trace context in console logs
logging.pattern.level=%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]
```

**Important**: Replace `your-service-name` with your actual service name.

**What this enables**:
- Traces are sent to `localhost:4318/v1/traces` (OTel Collector)
- Metrics are pushed every 10 seconds to `localhost:4318/v1/metrics`
- All logs automatically include `traceId` and `spanId` in MDC

---

### Step 1.3: Configure Structured Logging

**File**: `src/main/resources/logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Console appender (for local development) -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [traceId=%X{traceId:-} spanId=%X{spanId:-}] - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- JSON file appender (for Promtail to read) -->
    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <!-- Include trace context in JSON -->
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

**What this does**:
- Console logs show `traceId` and `spanId` for debugging
- File logs are JSON format with trace context as fields
- Promtail will read the JSON file and extract `traceId` as a label

**Action**: Create the `logs/` directory:
```bash
mkdir -p logs
```

---

### Step 1.4: Verify Instrumentation (Optional)

At this point, your application is instrumented but has nowhere to send data.

**Test locally**:
```bash
./gradlew bootRun
```

**Expected behavior**:
- Application starts successfully
- Console logs show `traceId` and `spanId` as empty (no collector running yet)
- File `logs/application.log` is created with JSON entries

**Sample log entry**:
```json
{
  "@timestamp": "2025-10-18T10:00:00.000Z",
  "message": "Started YourApplication",
  "logger_name": "com.example.YourApplication",
  "level": "INFO",
  "traceId": "",
  "spanId": "",
  "thread_name": "main"
}
```

---

## Phase 2: Observability Stack Setup

### Step 2.1: Create Directory Structure

```bash
cd your-project-root
mkdir observability
cd observability
```

All observability configuration files will go in this directory.

---

### Step 2.2: Create Docker Compose File

**File**: `docker-compose.yml` (in project root or `observability/`)

```yaml
services:
  # OpenTelemetry Collector - Central hub for all telemetry
  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP
    depends_on:
      - tempo
      - prometheus

  # Tempo - Distributed tracing backend
  tempo:
    image: grafana/tempo:latest
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./tempo-config.yaml:/etc/tempo.yaml
      - tempo-data:/var/tempo
    ports:
      - "3200:3200"

  # Loki - Log aggregation system
  loki:
    image: grafana/loki:latest
    command: ["-config.file=/etc/loki/local-config.yaml"]
    volumes:
      - ./loki-config.yaml:/etc/loki/local-config.yaml
      - loki-data:/loki
    ports:
      - "3100:3100"

  # Promtail - Log forwarder (reads files, sends to Loki)
  promtail:
    image: grafana/promtail:latest
    command: ["-config.file=/etc/promtail/config.yml"]
    volumes:
      - ./promtail-config.yaml:/etc/promtail/config.yml
      - ./logs:/var/log/app  # Mount your app's log directory
    depends_on:
      - loki

  # Prometheus - Metrics storage
  prometheus:
    image: prom/prometheus:latest
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/usr/share/prometheus/console_libraries'
      - '--web.console.templates=/usr/share/prometheus/consoles'
      - '--web.enable-remote-write-receiver'  # Required for OTLP metrics
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    extra_hosts:
      - "host.docker.internal:host-gateway"  # Allows scraping host machine

  # Grafana - Visualization and correlation
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

---

### Step 2.3: Configure OpenTelemetry Collector

**File**: `otel-collector-config.yaml`

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  # Batch telemetry for efficiency
  batch:
    timeout: 10s
    send_batch_size: 1024

  # Ensure service.name is preserved
  resource:
    attributes:
      - key: service.name
        action: upsert
        from_attribute: service.name

exporters:
  # Send traces to Tempo
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true

  # Send metrics to Prometheus via remote write
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write
    tls:
      insecure: true

  # Debug exporter (logs to collector stdout)
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

**Key Points**:
- Receives OTLP on ports 4317 (gRPC) and 4318 (HTTP)
- Routes traces to Tempo
- Routes metrics to Prometheus
- `debug` exporter helps troubleshoot (check `docker-compose logs otel-collector`)

---

### Step 2.4: Configure Tempo

**File**: `tempo-config.yaml`

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

---

### Step 2.5: Configure Loki

**File**: `loki-config.yaml`

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
  retention_period: 168h  # 7 days
  ingestion_rate_mb: 10
  ingestion_burst_size_mb: 20

table_manager:
  retention_deletes_enabled: true
  retention_period: 168h
```

---

### Step 2.6: Configure Promtail

**File**: `promtail-config.yaml`

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
          service_name: your-service-name  # CHANGE THIS
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

      # Extract labels (CRITICAL for correlation)
      - labels:
          level:
          traceId:
          spanId:
          service_name:

      # Use log's timestamp
      - timestamp:
          source: timestamp
          format: RFC3339

      # Output the message
      - output:
          source: message
```

**CRITICAL**: Change `service_name: your-service-name` to match your app.

**What this does**:
- Reads JSON logs from `/var/log/app/*.log` (mounted as `./logs` in Docker)
- Extracts `traceId` as a Loki **label** (enables querying by trace ID)
- Extracts `spanId` and `level` as labels too

---

### Step 2.7: Configure Prometheus

**File**: `prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  # Scrape your Spring Boot app's Prometheus endpoint
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']  # Host machine
        labels:
          application: 'your-service-name'  # CHANGE THIS
          environment: 'development'
```

**CRITICAL**: Change `application: 'your-service-name'` to match your app.

---

## Phase 3: Configuration & Correlation

### Step 3.1: Configure Grafana Datasources

**File**: `grafana-datasources.yaml`

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
      # Trace → Logs correlation
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
        query: '{service_name="your-service-name", traceId="${__trace.traceId}"}'

      # Trace → Metrics correlation
      tracesToMetrics:
        datasourceUid: prometheus
        spanStartTimeShift: '-5m'
        spanEndTimeShift: '5m'
        tags:
          - key: service.name
            value: service_name
        queries:
          - name: 'Request Rate'
            query: 'rate(http_server_requests_seconds_count{job="spring-boot-app"}[5m])'
          - name: 'Request Duration'
            query: 'rate(http_server_requests_seconds_sum{job="spring-boot-app"}[5m]) / rate(http_server_requests_seconds_count{job="spring-boot-app"}[5m])'
          - name: 'CPU Usage'
            query: 'process_cpu_usage{job="spring-boot-app"}'
          - name: 'Memory Usage'
            query: 'jvm_memory_used_bytes{job="spring-boot-app"}'
          - name: 'GC Rate'
            query: 'rate(jvm_gc_pause_seconds_count{job="spring-boot-app"}[5m])'

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
      # Logs → Traces correlation
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
      # Metrics → Traces correlation (requires exemplars)
      exemplarTraceIdDestinations:
        - name: trace_id
          datasourceUid: tempo
```

**CRITICAL**: Replace all instances of `your-service-name` with your actual service name.

**Correlation Setup**:
1. **Trace → Logs**: Query Loki by `traceId` label
2. **Trace → Metrics**: Show 5 key metrics at trace's timestamp
3. **Logs → Traces**: Extract traceId from logs and create clickable link

---

### Step 3.2: Start the Observability Stack

```bash
docker-compose up -d
```

**Verify all services are running**:
```bash
docker-compose ps
```

Expected output:
```
NAME                    STATUS
yourapp-grafana-1       Up
yourapp-loki-1          Up
yourapp-otel-collector-1 Up
yourapp-prometheus-1    Up
yourapp-promtail-1      Up
yourapp-tempo-1         Up
```

**Check for errors**:
```bash
docker-compose logs otel-collector | grep -i error
docker-compose logs promtail | grep -i error
```

---

## Phase 4: Verification & Testing

### Step 4.1: Start Your Application

```bash
./gradlew bootRun
```

**Watch for**:
- No OTLP connection errors
- Logs appear in `logs/application.log`

---

### Step 4.2: Generate Test Traffic

```bash
# Generate some traces
for i in {1..20}; do
  curl "http://localhost:8080/api/greet?name=Test$i"
  sleep 0.5
done
```

Replace `/api/greet` with any endpoint in your application.

---

### Step 4.3: Verify Traces

**Option 1: API**
```bash
curl -s "http://localhost:3200/api/search?tags=service.name=your-service-name" | jq '.traces[0].traceID'
```

**Option 2: Grafana UI**
1. Open http://localhost:3000
2. Go to Explore → Tempo
3. Search: `service.name="your-service-name"`
4. You should see traces listed

**Expected**: 20+ traces from your test traffic

---

### Step 4.4: Verify Logs

**Option 1: API**
```bash
curl -s 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="your-service-name"}' \
  --data-urlencode "start=$(date -u -v-5M +%s)000000000" \
  --data-urlencode "end=$(date -u +%s)000000000" \
  | jq '.data.result[0].values | length'
```

**Option 2: Grafana UI**
1. Go to Explore → Loki
2. Query: `{service_name="your-service-name"}`
3. You should see log entries

**Verify `traceId` label exists**:
```bash
curl -s 'http://localhost:3100/loki/api/v1/label/traceId/values' | jq '.data | length'
```

Expected: Non-zero number (count of unique trace IDs)

---

### Step 4.5: Verify Metrics

**Option 1: API**
```bash
curl -s 'http://localhost:9090/api/v1/query?query=process_cpu_usage{job="spring-boot-app"}' | jq '.data.result[0].value'
```

**Option 2: Grafana UI**
1. Go to Explore → Prometheus
2. Query: `process_cpu_usage{job="spring-boot-app"}`
3. You should see a graph

**Check available metrics**:
```bash
curl -s 'http://localhost:9090/api/v1/label/__name__/values' | jq '.data[] | select(contains("http_server") or contains("jvm"))' | head -10
```

---

### Step 4.6: Test Correlation

#### Test 1: Trace → Logs

1. Open Grafana → Explore → Tempo
2. Search for a trace
3. Click on a trace
4. Click "Logs for this span" button
5. **Expected**: Loki opens with 2+ log entries matching that trace ID

**If no logs appear**:
```bash
# Get a trace ID
TRACE_ID=$(curl -s "http://localhost:3200/api/search?tags=service.name=your-service-name" | jq -r '.traces[0].traceID')

# Check if Loki has logs with that trace ID
curl -s 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode "query={service_name=\"your-service-name\", traceId=\"$TRACE_ID\"}" \
  --data-urlencode "start=$(date -u -v-1H +%s)000000000" \
  --data-urlencode "end=$(date -u +%s)000000000" \
  | jq '.data.result | length'
```

If result is 0, see [Troubleshooting: Trace-Log Correlation](#trace-log-correlation-not-working).

#### Test 2: Trace → Metrics

1. Open a trace in Grafana
2. Look for "Related metrics" or similar button/dropdown
3. Click "CPU Usage" or "Memory Usage"
4. **Expected**: Opens Prometheus Explore with metrics from trace's time

**Note**: Time-based correlation may not be perfect. Adjust time range manually if needed.

#### Test 3: Logs → Traces

1. Open Grafana → Explore → Loki
2. Query: `{service_name="your-service-name"}`
3. Expand a log entry
4. Look for `traceId` field
5. Click the trace ID (should be a clickable link)
6. **Expected**: Opens trace view in Tempo

---

### Step 4.7: Extract Sample Trace ID

```bash
cat logs/application.log | jq -r 'select(.traceId != "") | .traceId' | head -n 1
```

Use this trace ID to manually test correlation in Grafana.

---

## Common Patterns & Decisions

### Decision 1: OTLP Only vs. Dual Metrics Export

**Option A: OTLP Only**
```properties
management.metrics.export.otlp.enabled=true
management.metrics.export.prometheus.enabled=false
```
- ✅ Cleaner architecture
- ✅ Push-based (firewall-friendly)
- ❌ Fewer labels in Prometheus

**Option B: Dual Mode (Recommended)**
```properties
management.metrics.export.otlp.enabled=true
management.metrics.export.prometheus.enabled=true
```
- ✅ Best of both worlds
- ✅ Rich labels from scraping
- ✅ Push reliability from OTLP
- ❌ Duplicate metrics (manageable)

**Recommendation**: Start with dual mode, migrate to OTLP-only later if needed.

---

### Decision 2: Sampling Rate

**Development**:
```properties
management.tracing.sampling.probability=1.0  # 100%
```

**Production**:
```properties
management.tracing.sampling.probability=0.1  # 10%
```

**High-traffic production**:
```properties
management.tracing.sampling.probability=0.01  # 1%
```

**Why reduce in production?**
- Traces are high-cardinality data
- 100% sampling can overwhelm storage
- 10% is usually sufficient for debugging

---

### Decision 3: Log Storage

**Current Setup**: File → Promtail → Loki

**Alternative**: Direct OTLP logs (future)
```java
// Not yet fully supported in Spring Boot
management.otlp.logs.endpoint=http://localhost:4318/v1/logs
```

**Why file-based for now?**
- Spring Boot doesn't fully support OTLP logs yet
- File-based is battle-tested
- Promtail is stable and feature-rich

---

### Pattern 1: Adding Custom Spans

```java
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;

@Service
public class MyService {
    private final Tracer tracer;

    public MyService(Tracer tracer) {
        this.tracer = tracer;
    }

    public void doSomething() {
        Span span = tracer.nextSpan().name("my-custom-operation").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span.start())) {
            // Your business logic
            span.tag("custom.tag", "value");
        } finally {
            span.end();
        }
    }
}
```

---

### Pattern 2: Adding Span Attributes

```java
@GetMapping("/api/user/{id}")
public User getUser(@PathVariable String id) {
    Span currentSpan = tracer.currentSpan();
    if (currentSpan != null) {
        currentSpan.tag("user.id", id);
        currentSpan.tag("user.type", user.getType());
    }
    return userService.findById(id);
}
```

---

### Pattern 3: Logging with Context

```java
@Slf4j
@RestController
public class MyController {

    @GetMapping("/api/process")
    public Response process(@RequestParam String data) {
        log.info("Starting process for data: {}", data);

        try {
            Result result = service.process(data);
            log.info("Process completed successfully");
            return Response.success(result);
        } catch (Exception e) {
            log.error("Process failed", e);
            return Response.error(e.getMessage());
        }
    }
}
```

**Automatic behavior**:
- All log statements include `traceId` and `spanId` in MDC
- No manual propagation needed
- Works across threads (with some config)

---

## Troubleshooting Guide

### Issue 1: No Traces Appearing in Tempo

**Symptoms**:
- Grafana shows no traces
- OTel Collector logs show no trace data

**Diagnosis**:
```bash
# Check if app is sending traces
docker-compose logs otel-collector | grep -i trace

# Check for connection errors in app logs
./gradlew bootRun 2>&1 | grep -i otlp
```

**Common Causes**:

1. **OTel Collector not reachable**
   - Solution: Verify `management.otlp.tracing.endpoint=http://localhost:4318/v1/traces`
   - Check: `curl -v http://localhost:4318/v1/traces` (should return 405 Method Not Allowed, which is OK)

2. **Sampling rate is 0**
   - Solution: Check `management.tracing.sampling.probability` is > 0

3. **Missing dependency**
   - Solution: Verify `io.opentelemetry:opentelemetry-exporter-otlp` is in dependencies

---

### Issue 2: Trace-Log Correlation Not Working

**Symptoms**:
- Traces appear in Tempo
- Logs appear in Loki
- "Logs for this span" shows no results

**Diagnosis**:
```bash
# Check if traceId is in logs
cat logs/application.log | jq '.traceId' | head -5

# Check if Loki has traceId as label
curl -s 'http://localhost:3100/loki/api/v1/label/traceId/values' | jq '.data | length'
```

**Common Causes**:

1. **`traceId` not extracted as label in Promtail**
   - Solution: Verify `promtail-config.yaml` has:
     ```yaml
     - labels:
         traceId:
     ```

2. **Grafana query uses wrong syntax**
   - Wrong: `{service_name="tel"} |~ "${__trace.traceId}"`
   - Correct: `{service_name="tel", traceId="${__trace.traceId}"}`
   - Solution: Update `grafana-datasources.yaml`

3. **Logs don't have traceId**
   - Check: `cat logs/application.log | jq 'select(.traceId == "") | .message'`
   - Solution: Verify `logback-spring.xml` includes:
     ```xml
     <includeMdcKeyName>traceId</includeMdcKeyName>
     ```

---

### Issue 3: No Metrics in Prometheus

**Symptoms**:
- Queries return no data
- `/actuator/prometheus` endpoint returns data but Prometheus doesn't have it

**Diagnosis**:
```bash
# Check if app exposes metrics
curl http://localhost:8080/actuator/prometheus | grep process_cpu_usage

# Check Prometheus targets
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'

# Check if OTLP metrics are being sent
docker-compose logs otel-collector | grep -i metric
```

**Common Causes**:

1. **Prometheus can't reach app**
   - Solution: Verify `host.docker.internal:8080` is reachable from container
   - Check: `docker exec yourapp-prometheus-1 wget -O- http://host.docker.internal:8080/actuator/prometheus`

2. **Remote write not enabled**
   - Solution: Verify `--web.enable-remote-write-receiver` flag in `docker-compose.yml`

3. **OTLP metrics not configured**
   - Solution: Check `management.metrics.export.otlp.enabled=true` in `application.properties`

---

### Issue 4: Grafana Shows "No Data" for Metrics from Trace

**Symptoms**:
- Click "CPU Usage" from trace
- Grafana opens but shows "No data"

**Diagnosis**:
```bash
# Get trace timestamp
curl -s "http://localhost:3200/api/traces/<TRACE_ID>" | jq -r '.batches[0].scopeSpans[0].spans[0].startTimeUnixNano'

# Check if metrics exist at that time
curl -s 'http://localhost:9090/api/v1/query?query=process_cpu_usage{job="spring-boot-app"}&time=<TIMESTAMP_IN_SECONDS>' | jq '.data.result'
```

**Common Causes**:

1. **Trace is too old**
   - Prometheus retention may have expired
   - Solution: Use a recent trace (< 15 days old)

2. **Query label mismatch**
   - Query uses `job="tel"` but metrics have `job="spring-boot-app"`
   - Solution: Update `grafana-datasources.yaml` queries to match your labels

3. **Time range issue**
   - Grafana time picker doesn't align with trace timestamp
   - Solution: Manually adjust time range to ±5 minutes around trace

---

### Issue 5: Application Won't Start

**Error**: `ClassNotFoundException` or `NoSuchMethodError`

**Diagnosis**:
```bash
./gradlew dependencies | grep -i micrometer
./gradlew dependencies | grep -i opentelemetry
```

**Common Causes**:

1. **Dependency conflicts**
   - Spring Boot version vs. Micrometer version mismatch
   - Solution: Use Spring Boot 3.2+ (has compatible Micrometer)

2. **Missing gRPC dependency**
   - Solution: Add `io.grpc:grpc-netty:1.68.1`

3. **Wrong Micrometer version**
   - Solution: Let Spring Boot manage versions (don't specify versions manually)

---

## Production Considerations

### 1. Security

**Current setup is for development only!**

Production changes:

1. **Disable anonymous Grafana access**
   ```yaml
   environment:
     - GF_AUTH_ANONYMOUS_ENABLED=false
     - GF_SECURITY_ADMIN_PASSWORD=<strong-password>
   ```

2. **Enable TLS for OTLP**
   ```yaml
   exporters:
     otlp/tempo:
       endpoint: tempo:4317
       tls:
         insecure: false
         cert_file: /path/to/cert.pem
   ```

3. **Authenticate Loki/Tempo**
   - Use auth proxies or native authentication

---

### 2. Scaling

**Single-instance limits**:
- Tempo: ~1000 traces/sec
- Loki: ~100MB/sec
- Prometheus: ~1M active series

**When to scale**:
- Use Tempo in clustered mode (S3/GCS backend)
- Use Loki with object storage (S3/GCS)
- Use Thanos for long-term Prometheus storage

---

### 3. Sampling Strategy

**Recommendation**:
```properties
# Production
management.tracing.sampling.probability=0.1

# High-traffic (>10k req/sec)
management.tracing.sampling.probability=0.01
```

**Advanced: Head-based sampling**
- Sample 100% of errors
- Sample 10% of slow requests (>1s)
- Sample 1% of fast, successful requests

(Requires custom sampler implementation)

---

### 4. Retention Policies

**Adjust in production**:

**Tempo** (traces):
```yaml
storage:
  trace:
    backend: s3
    s3:
      bucket: traces
      retention: 7d  # 7 days
```

**Loki** (logs):
```yaml
limits_config:
  retention_period: 168h  # 7 days (adjust based on compliance)
```

**Prometheus** (metrics):
```yaml
global:
  storage.tsdb.retention.time: 15d
  storage.tsdb.retention.size: 50GB
```

---

### 5. Alerting

**Example Prometheus alerts**:

```yaml
groups:
  - name: otel-alerts
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
        for: 5m
        annotations:
          summary: "High error rate detected"

      - alert: HighTraceSamplingDrop
        expr: rate(otelcol_processor_refused_spans[5m]) > 100
        annotations:
          summary: "OTel Collector dropping spans"
```

---

## Summary Checklist

Before considering your implementation complete:

### Application
- [ ] Dependencies added to `build.gradle`/`pom.xml`
- [ ] `application.properties` configured with correct endpoints
- [ ] `logback-spring.xml` created for JSON logging
- [ ] Service name configured consistently across all configs
- [ ] Application starts without errors
- [ ] Logs show `traceId` and `spanId`

### Observability Stack
- [ ] All 6 containers running (`docker-compose ps`)
- [ ] No errors in OTel Collector logs
- [ ] No errors in Promtail logs
- [ ] Tempo reachable on port 3200
- [ ] Loki reachable on port 3100
- [ ] Prometheus reachable on port 9090
- [ ] Grafana reachable on port 3000

### Verification
- [ ] Traces visible in Tempo
- [ ] Logs visible in Loki
- [ ] Metrics visible in Prometheus
- [ ] `traceId` exists as Loki label
- [ ] Trace → Logs correlation works
- [ ] Trace → Metrics correlation works (with manual time adjustment)
- [ ] Logs → Traces correlation works

### Documentation
- [ ] Service name documented
- [ ] Endpoints documented (if non-standard ports)
- [ ] Custom spans/attributes documented (if any)
- [ ] Sampling rate decision documented

---

## Next Steps

Once your basic setup is working:

1. **Create Dashboards**
   - Import community dashboards for Spring Boot
   - Create custom dashboards for your domain

2. **Add Custom Instrumentation**
   - Add spans for critical business operations
   - Add attributes for important context (user ID, tenant ID, etc.)

3. **Set Up Alerting**
   - Error rate alerts
   - Latency alerts
   - Resource usage alerts

4. **Optimize Performance**
   - Reduce sampling in production
   - Adjust retention periods
   - Consider batching settings

5. **Expand Observability**
   - Instrument database calls
   - Instrument external HTTP calls
   - Add business metrics

---

## Appendix: Quick Reference

### Ports

| Service | Port | Purpose |
|---------|------|---------|
| OTel Collector | 4317 | OTLP gRPC |
| OTel Collector | 4318 | OTLP HTTP |
| Tempo | 3200 | Tempo API |
| Loki | 3100 | Loki API |
| Promtail | 9080 | Promtail UI |
| Prometheus | 9090 | Prometheus UI |
| Grafana | 3000 | Grafana UI |
| Your App | 8080 | Application |

### Key Files to Customize

1. `application.properties` - Change `spring.application.name`
2. `promtail-config.yaml` - Change `service_name` label
3. `prometheus.yml` - Change `application` label
4. `grafana-datasources.yaml` - Change `service_name` in queries

### Useful Commands

```bash
# Restart observability stack
docker-compose restart

# View OTel Collector logs
docker-compose logs -f otel-collector

# Check Promtail is reading logs
docker-compose logs promtail | grep "Successfully read"

# Query Loki via CLI
curl 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="your-service"}' \
  --data-urlencode "start=$(date -u -v-10M +%s)000000000" \
  --data-urlencode "end=$(date -u +%s)000000000"

# Get a trace ID from logs
cat logs/application.log | jq -r '.traceId' | grep -v '^$' | head -1

# Check Prometheus targets health
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'
```

---

## Support Resources

- **OpenTelemetry Docs**: https://opentelemetry.io/docs/
- **Spring Boot Observability**: https://docs.spring.io/spring-boot/reference/actuator/observability.html
- **Grafana Tempo**: https://grafana.com/docs/tempo/latest/
- **Grafana Loki**: https://grafana.com/docs/loki/latest/
- **Micrometer Tracing**: https://micrometer.io/docs/tracing

---

**Document Version**: 1.0
**Last Updated**: 2025-10-18
**Maintained By**: Your Team

**License**: Internal Use Only
