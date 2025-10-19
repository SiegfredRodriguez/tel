# RabbitMQ + OpenTelemetry Distributed Tracing Demo

## Overview

This demonstration extends the microservices daisy chain to include **RabbitMQ messaging** with full OpenTelemetry trace propagation, showing that OTEL can trace beyond HTTP calls into asynchronous messaging systems.

## Architecture

```
HTTP Calls → RabbitMQ Messaging
┌─────────┐    ┌───────────┐    ┌───────────┐    ┌───────────┐    ┌───────────┐        ┌───────────┐
│ Gateway │───→│ Service A │───→│ Service B │───→│ Service C │───→│ Service D │──msg──→│ Service E │
└─────────┘    └───────────┘    └───────────┘    └───────────┘    └───────────┘        └───────────┘
   :8080          :8081            :8082            :8083            :8084                  :8085
                                                                        │                     │
                                                                        │    RabbitMQ         │
                                                                        └──────────────────►  │
                                                                           :5672              │
                                                                                              │
                                                                        ALL TRACES CONNECTED  │
                                                                        ◄─────────────────────┘
```

## Key Implementation Details

### 1. Service D: RabbitMQ Producer

**Location:** `microservices/service-d/`

**Controller.java:38-80**
- Receives HTTP request from Service C
- Manually injects trace context into RabbitMQ message headers
- Publishes message to `tel.chain.queue`

**Key OTEL Features:**
```java
// Add semantic conventions
currentSpan.tag("messaging.system", "rabbitmq");
currentSpan.tag("messaging.destination.name", QUEUE_NAME);
currentSpan.tag("messaging.operation.type", "send");
```

**RabbitMQConfig.java:48-65**
- Message post-processor injects trace context
- Adds `traceId`, `spanId`, and W3C `traceparent` header

```java
message.getMessageProperties().setHeader("traceId", context.traceId());
message.getMessageProperties().setHeader("spanId", context.spanId());

// W3C Trace Context format
String traceparent = String.format("00-%s-%s-01",
        context.traceId(),
        context.spanId());
message.getMessageProperties().setHeader("traceparent", traceparent);
```

### 2. Service E: RabbitMQ Consumer

**Location:** `microservices/service-e/`

**MessageConsumer.java:31-67**
- Listens to `tel.chain.queue`
- Extracts trace context from message headers
- Creates new span linked to producer's trace

**Key OTEL Features:**
```java
// Extract trace context
if (headers.containsKey("traceId")) {
    log.info("[service-e] Trace context from message: traceId={}, spanId={}",
            headers.get("traceId"), headers.get("spanId"));
}

// Add messaging semantics
currentSpan.tag("messaging.system", "rabbitmq");
currentSpan.tag("messaging.destination.name", QUEUE_NAME);
currentSpan.tag("messaging.operation.type", "process");
```

### 3. Docker Compose Updates

**docker-compose-microservices.yml:137-152**

Added RabbitMQ broker:
```yaml
rabbitmq:
  image: rabbitmq:3.13-management-alpine
  ports:
    - "5672:5672"   # AMQP protocol
    - "15672:15672" # Management UI
  healthcheck:
    test: ["CMD", "rabbitmq-diagnostics", "ping"]
    interval: 10s
```

## OTEL Semantic Conventions Compliance

### Messaging Spans

Following [OpenTelemetry messaging semantic conventions](https://opentelemetry.io/docs/specs/semconv/messaging/):

**Required Attributes:**
- ✅ `messaging.system` = "rabbitmq"
- ✅ `messaging.destination.name` = "tel.chain.queue"
- ✅ `messaging.operation.type` = "send" | "process"

**RabbitMQ-Specific Attributes:**
- ✅ `messaging.rabbitmq.destination.routing_key` (in message headers)
- ✅ `component.type` = "RabbitMQ Producer" | "RabbitMQ Consumer"

**W3C Trace Context:**
- ✅ `traceparent` header format: `00-{traceId}-{spanId}-01`
- ✅ Manual context propagation via message headers

## How to Test

### 1. Start All Services

```bash
docker-compose -f docker-compose-microservices.yml up -d --build
```

This starts:
- Gateway + Services A, B, C, D, E
- RabbitMQ broker
- OTEL Collector
- Tempo, Prometheus, Loki, Grafana

### 2. Wait for Services to be Ready

```bash
# Check all containers are running
docker-compose -f docker-compose-microservices.yml ps

# Wait for RabbitMQ to be healthy
docker logs tel-rabbitmq | grep "Server startup complete"

# Check services are up
for i in {1..5}; do
  curl -s http://localhost:8080/actuator/health && echo " - Gateway OK" || echo " - Gateway not ready"
  sleep 2
done
```

### 3. Trigger the Complete Chain

```bash
# Single request through the entire chain
curl "http://localhost:8080/api/chain?data=rabbitmq-test-1"
```

**Expected Response:**
```json
{
  "service": "gateway",
  "data": "rabbitmq-test-1",
  "timestamp": 1729331234567,
  "next": {
    "service": "service-a",
    "next": {
      "service": "service-b",
      "next": {
        "service": "service-c",
        "next": {
          "service": "service-d",
          "message": "Message sent to RabbitMQ queue: tel.chain.queue",
          "next": "service-e (via RabbitMQ)"
        }
      }
    }
  }
}
```

### 4. Verify RabbitMQ Message Processing

```bash
# Check service-e logs for message consumption
docker logs tel-service-e | grep "Received message"

# Example output:
# [service-e] Received message from RabbitMQ queue: tel.chain.queue
# [service-e] Trace context from message: traceId=3f89a2b1c4d5e6f7a8b9c0d1e2f3a4b5, spanId=1234567890abcdef
# [service-e] Message processing complete - End of chain
```

### 5. View Traces in Grafana

1. Open Grafana: http://localhost:3000
2. Navigate to **Explore**
3. Select **Tempo** datasource
4. Query:
   ```
   { service.name="gateway" }
   ```
5. Select a trace

**What You'll See:**
- **6 spans** in a single trace:
  1. Gateway → Service A (HTTP)
  2. Service A → Service B (HTTP)
  3. Service B → Service C (HTTP)
  4. Service C → Service D (HTTP)
  5. **Service D → RabbitMQ** (messaging.system=rabbitmq, operation=send)
  6. **RabbitMQ → Service E** (messaging.system=rabbitmq, operation=process)

### 6. Extract Trace ID

```bash
# From logs
docker logs tel-gateway | grep traceId | head -1

# Example output:
# INFO [gateway,3f89a2b1c4d5e6f7a8b9c0d1e2f3a4b5,1234567890abcdef]
#                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
#                             traceId
```

Or via Tempo API:
```bash
curl -s "http://localhost:3200/api/search?tags=service.name%3Dgateway" | jq '.traces[0].traceID'
```

### 7. RabbitMQ Management UI

Check message flow:
```
http://localhost:15672
Username: guest
Password: guest
```

Navigate to **Queues** → `tel.chain.queue` to see message statistics.

## Trace ID Example

```
TraceID: 3f89a2b1c4d5e6f7a8b9c0d1e2f3a4b5

Span Breakdown:
├── gateway          [HTTP GET /api/chain]        (root span)
│   └── service-a    [HTTP GET /api/process]
│       └── service-b [HTTP GET /api/process]
│           └── service-c [HTTP GET /api/process]
│               └── service-d [HTTP GET /api/process + RabbitMQ publish]
│                   └── service-e [RabbitMQ consume] ← CONNECTED!

All spans share the same traceId: 3f89a2b1c4d5e6f7a8b9c0d1e2f3a4b5
```

## Verification Commands

### Check Trace Propagation

```bash
# Generate multiple requests
for i in {1..3}; do
  curl -s "http://localhost:8080/api/chain?data=test-$i" > /dev/null
  echo "Request $i sent"
  sleep 1
done

# Check all services logged the same traceId
echo "=== Gateway ==="
docker logs tel-gateway | grep traceId | tail -3

echo "=== Service D (Producer) ==="
docker logs tel-service-d | grep "Publishing message" | tail -3

echo "=== Service E (Consumer) ==="
docker logs tel-service-e | grep "Trace context from message" | tail -3
```

### Query Tempo for Messaging Spans

```bash
# Find traces with RabbitMQ spans
curl -s "http://localhost:3200/api/search" \
  -H "Content-Type: application/json" \
  -d '{
    "tags": {
      "messaging.system": "rabbitmq"
    },
    "limit": 10
  }' | jq '.traces'
```

## OTEL Compliance Features

### ✅ Three Pillars of Observability

1. **Traces**: Distributed tracing across HTTP + RabbitMQ
2. **Metrics**: JVM, process, and custom application metrics
3. **Logs**: Structured JSON logs with traceId correlation

### ✅ Context Propagation

- **HTTP**: Automatic via Spring Boot + Micrometer
- **RabbitMQ**: Manual injection following W3C Trace Context spec

### ✅ Semantic Conventions

- **HTTP**: `http.method`, `http.status_code`, `http.target`
- **Messaging**: `messaging.system`, `messaging.destination.name`, `messaging.operation.type`
- **Service**: `service.name`, `service.version`, `deployment.environment`

### ✅ OTLP Export

All telemetry exported via OTLP (gRPC/HTTP) to collector on ports 4317/4318

### ✅ Backend Integration

- **Tempo**: Trace storage with TraceQL
- **Prometheus**: Metrics via remote write
- **Loki**: Logs with trace correlation
- **Grafana**: Unified observability

## Benefits of RabbitMQ + OTEL

1. **End-to-End Visibility**: Single trace from HTTP request → async message processing
2. **Performance Analysis**: Identify bottlenecks in messaging layer
3. **Error Correlation**: Link failures across synchronous and asynchronous boundaries
4. **SLA Monitoring**: Track latency through entire business transaction
5. **Debugging**: Follow request path even when services are decoupled

## Known Limitations

1. **Manual Context Propagation**: Spring Boot doesn't auto-instrument RabbitMQ yet
   - Solution: Custom `MessagePostProcessor` for header injection

2. **Span Linking**: Consumer span is part of same trace but requires header extraction
   - Solution: Extract `traceparent` and use `Tracer.startSpan()` with parent context

3. **Async Processing**: Consumer processing happens after producer returns
   - This is expected behavior for async messaging
   - Trace still connects all spans

## Future Enhancements

- [ ] Add span events for message acknowledgments
- [ ] Implement baggage for cross-cutting concerns (user ID, tenant ID)
- [ ] Add exemplars linking metrics to traces
- [ ] Use OTEL Java Agent for zero-code instrumentation
- [ ] Implement tail-based sampling for high-volume scenarios

## Conclusion

This demo proves that OpenTelemetry can maintain trace continuity across:
- ✅ Synchronous HTTP calls (Services A-D)
- ✅ Asynchronous messaging (RabbitMQ between D→E)
- ✅ Multiple observability backends (Tempo, Prometheus, Loki)

**The complete trace flows seamlessly from HTTP → RabbitMQ → Consumer, maintaining OTEL compliance throughout.**
