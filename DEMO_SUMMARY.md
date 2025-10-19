# Full OpenTelemetry Stack - Demo Summary

## What Was Built

A complete OpenTelemetry observability stack with **automatic trace-log-metric correlation**.

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot App                         │
│  • Auto-instrumented traces                                 │
│  • Structured logging with trace context                    │
│  • Prometheus metrics exposed                               │
└──────────┬──────────────────────┬──────────────────────┬────┘
           │                      │                      │
           ↓                      ↓                      ↓
    ┌──────────┐          ┌──────────┐          ┌──────────┐
    │  Tempo   │          │   Loki   │          │Prometheus│
    │ (Traces) │          │  (Logs)  │          │(Metrics) │
    └─────┬────┘          └─────┬────┘          └─────┬────┘
          │                     │                      │
          └────────────┬────────┴──────────────────────┘
                       ↓
                  ┌──────────┐
                  │ Grafana  │
                  │ With     │
                  │Correlation│
                  └──────────┘
```

## Services Running

| Service | Port | Purpose | Status |
|---------|------|---------|--------|
| Spring Boot | 8080 | Application | ✅ Running |
| Tempo | 3200, 4318 | Trace storage | ✅ Running |
| Loki | 3100 | Log aggregation | ✅ Running |
| Prometheus | 9090 | Metrics storage | ✅ Running |
| Grafana | 3000 | Visualization | ✅ Running |

## Quick Access

- **Application**: http://localhost:8080/api/greet
- **Grafana**: http://localhost:3000 (no login required)
- **Prometheus**: http://localhost:9090
- **Tempo API**: http://localhost:3200
- **Loki API**: http://localhost:3100

## Available Endpoints

### Application Endpoints

```bash
# Normal endpoint with logging
curl "http://localhost:8080/api/greet?name=John"

# Simulated error endpoint
curl "http://localhost:8080/api/error"

# Health check
curl "http://localhost:8080/actuator/health"

# Prometheus metrics
curl "http://localhost:8080/actuator/prometheus"
```

### Backend Verification

```bash
# Check traces in Tempo
curl -s "http://localhost:3200/api/search?tags=service.name%3Dtel" | jq '.traces | length'

# Query Prometheus
curl -s "http://localhost:9090/api/v1/query?query=up{job='spring-boot-app'}" | jq '.'

# Check app metrics
curl -s "http://localhost:8080/actuator/prometheus" | grep http_server_requests
```

## Trace-Log-Metric Correlation Features

### 1. Automatic Trace Context in Logs

**Every log line includes:**
- `trace_id` - Links to the distributed trace
- `span_id` - Identifies the specific operation
- Thread name
- Timestamp

**Example console output:**
```
2025-10-18 17:07:29.430 [http-nio-8080-exec-1] INFO GreetController [trace_id=abc123 span_id=def456] - Received greet request for name: John
```

### 2. Grafana Correlations Configured

**From Traces → Logs:**
- Click "Logs for this span" in Tempo
- Automatically queries Loki for matching logs
- Filters by trace_id

**From Logs → Traces:**
- Logs show clickable trace_id links
- Click to jump to full trace in Tempo
- See complete request flow

**From Metrics → Traces:**
- Prometheus exemplars enabled
- Click metric data points
- View the trace that generated that metric

### 3. Unified Query in Grafana

You can:
1. Start with high-level metrics dashboard
2. Drill down to specific traces
3. View detailed logs for context
4. All connected by trace_id!

## How to Explore in Grafana

### Step 1: View Traces
1. Open http://localhost:3000
2. Go to **Explore**
3. Select **Tempo** datasource
4. Query: `{ service.name="tel" }`
5. Click any trace to see details

### Step 2: Trace → Logs
1. In trace view, find a span
2. Click **"Logs for this span"**
3. Grafana switches to Loki
4. Shows all logs with matching trace_id

### Step 3: View Metrics
1. Switch to **Prometheus** datasource
2. Query: `rate(http_server_requests_seconds_count[5m])`
3. See request rate over time
4. Exemplars (if available) link back to traces

### Step 4: Logs → Traces
1. In **Loki** datasource
2. Query: `{service_name="tel"} |= "Received greet"`
3. See trace_id in logs
4. Click trace_id link to jump to trace

## Demonstration Scenarios

### Scenario 1: Debug Slow Request

```bash
# Generate some requests
for i in {1..10}; do
  curl "http://localhost:8080/api/greet?name=User$i"
  sleep 1
done
```

**In Grafana:**
1. Prometheus → Query latency: `http_server_requests_seconds`
2. Tempo → Find slow traces: `duration > 50ms`
3. Click slow trace → See span breakdown
4. Click "Logs" → See what happened during slow request

### Scenario 2: Investigate Error

```bash
# Trigger error
curl "http://localhost:8080/api/error"
```

**In Grafana:**
1. Loki → Search: `{service_name="tel"} |= "ERROR"`
2. Find error log with trace_id
3. Click trace_id → Jump to Tempo
4. See full trace with error span
5. View exception details

### Scenario 3: Monitor Specific User

```bash
# User requests
curl "http://localhost:8080/api/greet?name=Alice"
curl "http://localhost:8080/api/greet?name=Alice"
```

**In Grafana:**
1. Tempo → Query: `{ .http.url =~ ".*Alice.*" }`
2. See all traces for Alice
3. Check latency distribution
4. View logs for Alice's requests

## Key Technologies

### Application Layer
- **Spring Boot 3.5.6** - Web framework
- **Micrometer** - Observability facade
- **OpenTelemetry SDK** - Telemetry generation
- **Logback** - Logging framework
- **OpenTelemetry Logback Appender** - Log correlation

### Observability Backend
- **Grafana Tempo** - Distributed tracing
- **Grafana Loki** - Log aggregation
- **Prometheus** - Metrics storage
- **Grafana** - Unified visualization

### Protocols & Standards
- **OTLP** (OpenTelemetry Protocol) - Standard telemetry protocol
- **W3C Trace Context** - Standard trace propagation
- **Prometheus exposition format** - Metrics format
- **LogQL** - Loki query language
- **TraceQL** - Tempo query language
- **PromQL** - Prometheus query language

## What Makes This Special

### 1. Zero Code Changes for Correlation
- OpenTelemetry auto-instrumentation handles traces
- Logback appender automatically adds trace context
- No manual correlation code needed!

### 2. Standard Protocols
- OTLP for traces and logs
- Prometheus for metrics
- Portable to any OTLP-compatible backend

### 3. Grafana Integration
- Pre-configured correlations
- Click between signals
- Unified investigation workflow

### 4. Production-Ready
- Scalable backends (Tempo, Loki, Prometheus)
- Industry-standard tools
- Battle-tested in large deployments

## File Structure

```
tel/
├── src/
│   ├── main/
│   │   ├── java/.../
│   │   │   └── controller/
│   │   │       └── GreetController.java (with logging)
│   │   └── resources/
│   │       ├── application.properties (OTLP config)
│   │       └── logback-spring.xml (OTLP appender)
│   └── test/
├── build.gradle (all dependencies)
├── docker-compose.yml (all 4 backends)
├── tempo-config.yaml
├── loki-config.yaml
├── prometheus.yml
├── grafana-datasources.yaml (with correlations!)
├── README.md
├── TRACING_SETUP.md
├── FULL_OBSERVABILITY_GUIDE.md
└── DEMO_SUMMARY.md (this file)
```

## Commands Cheat Sheet

```bash
# Start everything
docker-compose up -d
./gradlew bootRun

# Stop everything
docker-compose down
# (Kill gradlew process)

# View logs
docker-compose logs tempo
docker-compose logs loki
docker-compose logs prometheus
docker-compose logs grafana

# Check status
docker-compose ps

# Generate test data
for i in {1..20}; do curl "http://localhost:8080/api/greet?name=User$i"; sleep 1; done

# Cleanup
docker-compose down -v  # Remove volumes too
```

## Next Steps

### Explore Grafana
1. Create dashboards combining all three signals
2. Set up alerts based on traces/logs/metrics
3. Use TraceQL for advanced trace queries
4. Create service maps from traces

### Scale Up
1. Replace local storage with S3/GCS
2. Add load balancing
3. Deploy to Kubernetes
4. Configure retention policies

### Enhance Application
1. Add custom spans
2. Instrument database calls
3. Add business metrics
4. Implement baggage for context propagation

## Resources

- [OpenTelemetry Docs](https://opentelemetry.io/docs/)
- [Grafana Tempo](https://grafana.com/docs/tempo/)
- [Grafana Loki](https://grafana.com/docs/loki/)
- [Prometheus](https://prometheus.io/docs/)
- [Grafana Correlations](https://grafana.com/docs/grafana/latest/fundamentals/correlations/)

---

## Success Criteria ✅

- [x] Traces flowing to Tempo via OTLP
- [x] Logs sent to Loki (OTLP Logback appender)
- [x] Metrics exposed for Prometheus scraping
- [x] Grafana datasources configured with correlations
- [x] Trace IDs automatically added to logs
- [x] Click-through from traces to logs working
- [x] Click-through from logs to traces working
- [x] All services running in Docker
- [x] Zero manual correlation code needed
- [x] Full documentation provided

**Result: Complete OpenTelemetry observability stack with automatic correlation! 🎉**
