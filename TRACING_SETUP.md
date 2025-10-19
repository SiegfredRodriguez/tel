# OpenTelemetry Tracing Integration Guide

Complete guide for integrating distributed tracing from Spring Boot through Tempo to Grafana.

## Overview

This guide explains how to instrument a Spring Boot application with OpenTelemetry and send traces to Grafana Tempo for visualization in Grafana.

**Tracing Flow:**
```
Spring Boot App → OpenTelemetry SDK → OTLP HTTP Exporter → Tempo → Grafana
```

---

## Part 1: Spring Boot Application Instrumentation

### 1.1 Add Dependencies

Add these dependencies to `build.gradle` to enable OpenTelemetry tracing:

```gradle
dependencies {
    // Actuator provides management endpoints and observability support
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Micrometer bridges Spring's Observation API to OpenTelemetry
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'

    // OpenTelemetry OTLP exporter for sending traces
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'

    // gRPC Netty for OTLP transport (supports both HTTP and gRPC)
    implementation 'io.grpc:grpc-netty:1.68.1'
}
```

**What each dependency does:**
- `spring-boot-starter-actuator`: Enables Spring Boot's observability features
- `micrometer-tracing-bridge-otel`: Converts Spring's observations into OpenTelemetry spans
- `opentelemetry-exporter-otlp`: Exports traces using the OTLP protocol
- `grpc-netty`: Transport layer for OTLP (required even for HTTP)

### 1.2 Configure OpenTelemetry

Add to `application.properties`:

```properties
# Application identity in traces
spring.application.name=tel

# Tracing configuration
management.tracing.sampling.probability=1.0
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces

# Disable other telemetry signals
management.metrics.export.otlp.enabled=false
management.otlp.logs.export.enabled=false
```

**Configuration explained:**

| Property | Value | Purpose |
|----------|-------|---------|
| `spring.application.name` | `tel` | Sets the `service.name` attribute in traces |
| `management.tracing.sampling.probability` | `1.0` | Sample 100% of requests (use 0.1 for 10% in production) |
| `management.otlp.tracing.endpoint` | `http://localhost:4318/v1/traces` | Tempo's OTLP HTTP receiver endpoint |
| `management.metrics.export.otlp.enabled` | `false` | Disable metrics export to OTLP |
| `management.otlp.logs.export.enabled` | `false` | Disable logs export to OTLP |

**Important Notes:**
- The endpoint MUST include the full path `/v1/traces` for OTLP HTTP
- Use `http://` scheme for OTLP HTTP (not `grpc://`)
- Port 4318 is the standard OTLP HTTP port
- Port 4317 is for OTLP gRPC (not used here)

### 1.3 Automatic Instrumentation

Spring Boot automatically instruments your application when the above dependencies are present. **No code changes required!**

What gets traced automatically:
- ✅ HTTP server requests (incoming)
- ✅ HTTP client requests (outgoing)
- ✅ Database queries (with JDBC)
- ✅ Message broker operations
- ✅ Async operations

Example trace from a simple controller:

```java
@RestController
@RequestMapping("/api")
public class GreetController {

    @GetMapping("/greet")
    public Map<String, String> greet() {
        return Map.of("message", "hello");
    }
}
```

This automatically creates a trace span with:
- Span name: `GET /api/greet`
- Service name: `tel` (from spring.application.name)
- HTTP attributes: method, status code, URL, etc.
- Trace ID and Span ID

---

## Part 2: Tempo Backend Configuration

Tempo receives, stores, and serves traces for querying.

### 2.1 Tempo Configuration File

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

**Configuration breakdown:**

#### Server Block
```yaml
server:
  http_listen_port: 3200
```
- Tempo's main HTTP API port for queries
- Grafana connects to this port to search traces

#### Distributor Receivers
```yaml
distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318
        grpc:
          endpoint: 0.0.0.0:4317
```
- **Distributor**: Component that receives incoming traces
- **OTLP receivers**: Accept traces in OpenTelemetry Protocol format
- `http.endpoint: 0.0.0.0:4318`: Listens on all interfaces, port 4318
- `grpc.endpoint: 0.0.0.0:4317`: gRPC receiver (alternative to HTTP)
- ⚠️ **Critical**: Explicitly specifying endpoints ensures they're enabled

#### Storage
```yaml
storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
```
- Uses local filesystem storage (for development)
- Production should use S3, GCS, or Azure Blob Storage
- Path `/var/tempo/traces` is where traces are persisted

### 2.2 Deploy Tempo with Docker Compose

Add to `docker-compose.yml`:

```yaml
tempo:
  image: grafana/tempo:latest
  command: [ "-config.file=/etc/tempo.yaml" ]
  volumes:
    - ./tempo-config.yaml:/etc/tempo.yaml
    - tempo-data:/var/tempo
  ports:
    - "3200:3200"   # Tempo HTTP API
    - "4317:4317"   # OTLP gRPC receiver
    - "4318:4318"   # OTLP HTTP receiver
```

**Port mappings:**
- `3200`: Tempo's query API (Grafana connects here)
- `4317`: OTLP gRPC ingestion endpoint
- `4318`: OTLP HTTP ingestion endpoint (used by Spring Boot)

**Volume mappings:**
- `./tempo-config.yaml:/etc/tempo.yaml`: Mount configuration
- `tempo-data:/var/tempo`: Persist trace data

---

## Part 3: Grafana Visualization Setup

### 3.1 Configure Tempo Datasource

Create `grafana-datasources.yaml`:

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

**Datasource configuration:**

| Field | Value | Purpose |
|-------|-------|---------|
| `name` | `Tempo` | Display name in Grafana |
| `type` | `tempo` | Datasource plugin type |
| `access` | `proxy` | Grafana proxies requests (vs browser direct) |
| `url` | `http://tempo:3200` | Tempo API endpoint (container name in Docker) |
| `uid` | `tempo` | Unique identifier for the datasource |
| `isDefault` | `true` | Makes this the default datasource |
| `editable` | `true` | Allows editing in UI |

⚠️ **Important**: Use container name `tempo` (not `localhost`) because Grafana runs in Docker network.

### 3.2 Deploy Grafana

Add to `docker-compose.yml`:

```yaml
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
```

**Configuration details:**

- **Provisioning**: Datasource YAML is auto-loaded at startup
- **Anonymous auth**: Enabled for easy access (development only!)
- **Anonymous role**: Admin (full access without login)
- **Port**: 3000 is Grafana's default HTTP port
- **Dependencies**: Ensures Tempo starts before Grafana

---

## Part 4: Trace Flow Verification

### 4.1 Component Communication

```
┌─────────────────┐
│  Spring Boot    │
│  :8080          │
└────────┬────────┘
         │ OTLP HTTP
         │ POST http://localhost:4318/v1/traces
         ▼
┌─────────────────┐
│  Tempo          │
│  :4318 (OTLP)   │
│  :3200 (API)    │
└────────┬────────┘
         │ Query API
         │ GET http://tempo:3200/api/search
         ▼
┌─────────────────┐
│  Grafana        │
│  :3000          │
└─────────────────┘
```

### 4.2 Trace Attributes

When a request is made to `/api/greet`, OpenTelemetry automatically captures:

**Span Attributes:**
```
service.name: tel
http.method: GET
http.route: /api/greet
http.status_code: 200
http.url: http://localhost:8080/api/greet
thread.name: http-nio-8080-exec-1
```

**Trace Context:**
```
trace_id: 32-character hex string (globally unique)
span_id: 16-character hex string (unique within trace)
parent_span_id: null (this is root span)
```

### 4.3 Verification Steps

**Step 1: Generate a trace**
```bash
curl http://localhost:8080/api/greet
```

**Step 2: Query Tempo directly**
```bash
curl -s "http://localhost:3200/api/search?tags=service.name%3Dtel" | jq '.'
```

Expected output:
```json
{
  "traces": [
    {
      "traceID": "abc123...",
      "rootServiceName": "tel",
      "rootTraceName": "GET /api/greet"
    }
  ],
  "metrics": {
    "completedJobs": 1,
    "totalJobs": 1
  }
}
```

**Step 3: View in Grafana**
1. Open http://localhost:3000
2. Navigate to **Explore** (compass icon)
3. Select **Tempo** datasource
4. Click **Search** or use TraceQL query:
   ```
   { service.name="tel" }
   ```

---

## Part 5: Understanding Trace Data

### 5.1 Trace Structure

A single request creates this structure:

```
Trace (trace_id: abc123...)
└── Span: GET /api/greet (span_id: def456...)
    ├── Start time: 2025-10-18T08:52:30Z
    ├── Duration: 15ms
    ├── Status: OK
    └── Attributes:
        ├── service.name: tel
        ├── http.method: GET
        ├── http.route: /api/greet
        └── http.status_code: 200
```

### 5.2 Trace Sampling

```properties
management.tracing.sampling.probability=1.0
```

**Sampling rates:**
- `1.0` = 100% (trace every request) - **Development**
- `0.1` = 10% (trace 1 in 10 requests) - **Production**
- `0.01` = 1% (trace 1 in 100 requests) - **High traffic**

Sampling reduces:
- Network bandwidth to Tempo
- Storage costs
- Processing overhead

But you lose:
- Visibility into some requests
- Ability to debug specific issues

### 5.3 Trace Context Propagation

When your service calls another service:

```
Service A (tel)
  └─ HTTP Request → Service B
       └─ Trace Context in headers:
          ├─ traceparent: 00-{trace-id}-{span-id}-01
          └─ tracestate: (optional)
```

Headers automatically include:
- `traceparent`: W3C standard trace context
- Parent span info
- Sampling decision

Service B continues the same trace!

---

## Part 6: Troubleshooting

### Issue: No traces appearing in Tempo

**Check 1: Tempo is receiving traces**
```bash
docker-compose logs tempo | grep -i "otlp\|receiver"
```
Look for: "Starting OTLP receiver"

**Check 2: Application is sending traces**
```bash
# In application logs, look for:
# No ERROR messages from HttpExporter
# Successful span export (in debug mode)
```

**Check 3: Network connectivity**
```bash
# From host, test Tempo endpoint
curl -v http://localhost:4318/v1/traces

# Should connect (may reject GET, but connection works)
```

**Check 4: Configuration**
```properties
# Verify endpoint in application.properties
management.otlp.tracing.endpoint=http://localhost:4318/v1/traces
#                                  ^^^^^          ^^^^  ^^^^^^^^
#                                  Must be http   Port  Full path required
```

### Issue: Tempo rejects traces

**Symptoms:**
```
ERROR i.o.exporter.internal.http.HttpExporter : Failed to export spans.
Connection reset
```

**Solution:**
Restart Spring Boot app AFTER Tempo is fully started:
```bash
# 1. Ensure Tempo is running
docker-compose up -d tempo

# 2. Wait 5 seconds for startup
sleep 5

# 3. Start Spring Boot
./gradlew bootRun
```

### Issue: Grafana can't query Tempo

**Check datasource URL:**
- ✅ Correct: `http://tempo:3200` (container name)
- ❌ Wrong: `http://localhost:3200` (wrong in Docker network)

**Test connection in Grafana:**
1. Settings → Data Sources → Tempo
2. Click "Save & Test"
3. Should show green "Data source is working"

---

## Part 7: Production Considerations

### 7.1 Security

**Application to Tempo:**
```properties
# Use TLS in production
management.otlp.tracing.endpoint=https://tempo.prod.example.com/v1/traces

# Add authentication headers if needed
management.otlp.tracing.headers.Authorization=Bearer ${TEMPO_TOKEN}
```

**Grafana authentication:**
```yaml
# Disable anonymous auth in production
environment:
  - GF_AUTH_ANONYMOUS_ENABLED=false
  # Configure OAuth, LDAP, or other auth
```

### 7.2 Sampling Strategy

Use probability-based sampling in production:

```properties
# High-traffic service: 1% sampling
management.tracing.sampling.probability=0.01

# Medium-traffic: 10% sampling
management.tracing.sampling.probability=0.1

# Low-traffic: 100% sampling
management.tracing.sampling.probability=1.0
```

### 7.3 Tempo Storage

Replace local storage with cloud storage:

```yaml
storage:
  trace:
    backend: s3
    s3:
      bucket: tempo-traces
      endpoint: s3.amazonaws.com
      access_key: ${AWS_ACCESS_KEY}
      secret_key: ${AWS_SECRET_KEY}
```

### 7.4 Resource Limits

Set JVM options for OpenTelemetry:

```bash
JAVA_OPTS="-Xms512m -Xmx2g \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -Dotel.traces.exporter=otlp"
```

---

## Summary

### What was configured:

1. **Spring Boot**: Auto-instrumentation via Micrometer + OpenTelemetry
2. **OTLP Exporter**: Sends traces over HTTP to Tempo on port 4318
3. **Tempo**: Receives traces via OTLP HTTP, stores locally
4. **Grafana**: Queries Tempo for trace visualization

### Key configuration points:

- ✅ Full OTLP endpoint path: `/v1/traces`
- ✅ Explicit Tempo receiver endpoints in config
- ✅ Container networking: Use container names, not localhost
- ✅ Startup order: Tempo first, then Spring Boot
- ✅ 100% sampling for development visibility

### Result:

Every HTTP request to your Spring Boot application is automatically traced, sent to Tempo, and visible in Grafana with zero code changes required!
