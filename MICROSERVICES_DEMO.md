# Distributed Tracing Demo - Microservices Chain

This demo showcases OpenTelemetry distributed tracing across 5 microservices that call each other in a chain.

## Architecture

```
Client → Gateway (8080) → Service-A (8081) → Service-B (8082) → Service-C (8083) → Service-D (8084)
```

All services send:
- **Traces** → OTel Collector → Tempo
- **Metrics** → OTel Collector → Prometheus
- **Logs** → (container logs, can be extended)

## Services

| Service | Port | Next Service | Description |
|---------|------|--------------|-------------|
| gateway | 8080 | service-a | Entry point |
| service-a | 8081 | service-b | First hop |
| service-b | 8082 | service-c | Second hop |
| service-c | 8083 | service-d | Third hop |
| service-d | 8084 | none | End of chain |

## Quick Start

### 1. Build and Start

```bash
# Stop any running single-service setup
docker-compose down

# Build and start the microservices
docker-compose -f docker-compose-microservices.yml up --build -d
```

**Note**: First build will take 5-10 minutes as it builds the same Docker image 5 times.

### 2. Wait for Services

```bash
# Check all services are running
docker-compose -f docker-compose-microservices.yml ps

# Watch logs
docker-compose -f docker-compose-microservices.yml logs -f gateway
```

Wait until you see "Started TelApplication" for all services.

### 3. Generate a Distributed Trace

```bash
# Call the gateway's /api/chain endpoint
curl "http://localhost:8080/api/chain?data=test123"
```

**Expected Response**:
```json
{
  "service": "gateway",
  "data": "test123",
  "timestamp": 1760785000000,
  "next": {
    "service": "service-a",
    "data": "test123",
    "timestamp": 1760785000100,
    "next": {
      "service": "service-b",
      "data": "test123",
      "timestamp": 1760785000200,
      "next": {
        "service": "service-c",
        "data": "test123",
        "timestamp": 1760785000300,
        "next": {
          "service": "service-d",
          "data": "test123",
          "timestamp": 1760785000400,
          "message": "End of chain"
        }
      }
    }
  }
}
```

### 4. View the Distributed Trace

1. Open Grafana: http://localhost:3000
2. Go to **Explore** → **Tempo**
3. Search for traces:
   - Query: `service.name="gateway"`
   - Or use TraceQL: `{ span.http.target="/api/chain" }`
4. Click on a trace

**What You'll See**:
- **5 spans** in a single trace (one per service)
- Each span shows the service name and duration
- Parent-child relationship visualized
- Total trace duration across all services

### 5. View Service Map

In Grafana:
1. Go to **Explore** → **Tempo**
2. Click **Service Graph** tab
3. You'll see: `gateway → service-a → service-b → service-c → service-d`

## Observability Features

### Distributed Tracing

**Trace Propagation**:
- Automatic via Spring Boot's `RestClient`
- Trace ID passed in HTTP headers (`traceparent`)
- No manual code needed

**What's Traced**:
- HTTP server request (incoming)
- HTTP client request (outgoing to next service)
- Each service adds its own span to the trace

### Metrics

Each service exports:
- `http_server_requests_seconds_count` - Request count
- `http_server_requests_seconds_sum` - Total duration
- `http_client_requests_seconds_count` - Outgoing requests
- `process_cpu_usage` - CPU usage
- `jvm_memory_used_bytes` - Memory usage

**Query in Prometheus** (http://localhost:9090):
```promql
# Request rate per service
rate(http_server_requests_seconds_count[5m])

# Outgoing requests (shows the chain)
rate(http_client_requests_seconds_count[5m])
```

### Logs

Check logs for a specific service:
```bash
docker-compose -f docker-compose-microservices.yml logs gateway | grep "Received chain request"
```

Each log includes:
- `traceId` - Links to trace
- `spanId` - Links to specific span
- Service name

## Advanced Usage

### Generate Multiple Traces

```bash
# Generate 20 traces
for i in {1..20}; do
  curl "http://localhost:8080/api/chain?data=test$i"
  sleep 0.5
done
```

### View Trace Details

In Grafana Tempo:
1. Select a trace
2. Click **"Logs for this span"** - see logs from all services
3. Click **"Related metrics"** - see metrics at trace time
4. Expand each span to see:
   - Service name
   - HTTP method and URL
   - Status code
   - Duration

### Find Slow Traces

TraceQL query:
```
{ duration > 500ms }
```

### Find Error Traces

TraceQL query:
```
{ status = error }
```

### Filter by Service

```
{ service.name = "service-b" }
```

## Testing Scenarios

### 1. Normal Flow
```bash
curl "http://localhost:8080/api/chain?data=normal"
```
- All 5 services respond
- Trace shows 5 spans
- Total duration ~50-200ms

### 2. Original Endpoint (No Chain)
```bash
curl "http://localhost:8080/api/greet?name=Test"
```
- Only gateway responds
- Trace shows 1 span
- Faster response

### 3. Direct Service Access
```bash
# Call service-c directly (it will call only service-d)
curl "http://localhost:8083/api/chain?data=partial"
```
- Trace shows 2 spans (service-c → service-d)
- Demonstrates you can start mid-chain

## Troubleshooting

### Services Not Starting

```bash
# Check specific service logs
docker-compose -f docker-compose-microservices.yml logs service-a

# Check if OTel Collector is reachable
docker exec tel-gateway curl http://otel-collector:4318/v1/traces
```

### No Traces Appearing

```bash
# Check if services are sending traces
docker-compose -f docker-compose-microservices.yml logs otel-collector | grep -i trace

# Verify Tempo has traces
curl "http://localhost:3200/api/search?tags=service.name=gateway"
```

### Service Can't Reach Next Service

```bash
# Check network connectivity
docker exec tel-gateway ping service-a

# Check if service is listening
docker exec tel-service-a netstat -tuln | grep 8081
```

## Architecture Decisions

### Why Same Docker Image?

- Simpler to demonstrate
- Real microservices would be separate apps
- Environment variables configure behavior

### Why RestClient?

- Built into Spring Boot 3.x
- Automatic trace propagation
- No additional dependencies

### Why No Promtail?

- Container logs go to Docker
- Can be added with volume mounts
- Simplified for demo purposes

## Cleanup

```bash
# Stop all services
docker-compose -f docker-compose-microservices.yml down

# Remove images
docker-compose -f docker-compose-microservices.yml down --rmi all

# Remove volumes (clears all data)
docker-compose -f docker-compose-microservices.yml down -v
```

## Next Steps

1. **Add Error Simulation**:
   - Make service-c occasionally fail
   - See error traces in Grafana

2. **Add Latency**:
   - Add `Thread.sleep()` in service-b
   - Find slow spans in traces

3. **Add Custom Spans**:
   - Add business logic spans
   - Tag spans with business data

4. **Add Logs**:
   - Mount log volumes
   - Set up Promtail
   - See logs correlated with traces

5. **Add Alerting**:
   - Alert on high error rate
   - Alert on slow traces
   - Alert on service down

## Trace Examples

### Example 1: Successful Trace
```
gateway (50ms)
  ├─ HTTP GET /api/chain
  └─ service-a (40ms)
      ├─ HTTP GET /api/chain
      └─ service-b (30ms)
          ├─ HTTP GET /api/chain
          └─ service-c (20ms)
              ├─ HTTP GET /api/chain
              └─ service-d (10ms)
                  └─ HTTP GET /api/chain
```

### Example 2: Partial Chain (starting at service-b)
```
service-b (30ms)
  ├─ HTTP GET /api/chain
  └─ service-c (20ms)
      ├─ HTTP GET /api/chain
      └─ service-d (10ms)
          └─ HTTP GET /api/chain
```

## Key Takeaways

1. **Automatic Propagation**: Trace context propagates automatically through HTTP headers
2. **Single Trace ID**: One request creates one trace across all services
3. **Span Hierarchy**: Each service adds a child span
4. **No Code Changes**: OpenTelemetry instrumentation is automatic
5. **Correlation**: Traces, logs, and metrics all linked by trace ID

---

**Tip**: Leave this running and generate various traffic patterns to see different trace shapes in Grafana!
