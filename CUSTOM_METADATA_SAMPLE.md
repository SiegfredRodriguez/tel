# Custom Metadata Sample

## Overview
This trace demonstrates custom metadata (`created.by="J"` and `built.with="Java"`) added to all services in the microservices chain.

## Sample Trace

**Trace ID:** `a78be02de50ab58436ccb82fa4b2ea7c`

### Services Involved
All 6 services in the chain have the custom metadata:

1. **gateway** → 2. **service-a** → 3. **service-b** → 4. **service-c** → 5. **service-d** → 6. **service-e**

### Custom Metadata on Each Service
```
Service: gateway
  ✓ created.by: J
  ✓ built.with: Java
  ✓ service.version: 1.0.0

Service: service-a
  ✓ created.by: J
  ✓ built.with: Java
  ✓ service.version: 1.0.0

Service: service-b
  ✓ created.by: J
  ✓ built.with: Java
  ✓ service.version: 1.0.0

Service: service-c
  ✓ created.by: J
  ✓ built.with: Java
  ✓ service.version: 1.0.0

Service: service-d
  ✓ created.by: J
  ✓ built.with: Java
  ✓ service.version: 1.0.0

Service: service-e
  ✓ created.by: J
  ✓ built.with: Java
  ✓ service.version: 1.0.0
```

## Viewing in Grafana

### Method 1: Direct Trace ID Search
1. Open [http://localhost:3000](http://localhost:3000)
2. Click **Explore** (compass icon on left sidebar)
3. Select **Tempo** datasource
4. Paste trace ID: `a78be02de50ab58436ccb82fa4b2ea7c`
5. Press Enter

### Method 2: Query by Custom Attributes
Search for all traces with your custom metadata:

```traceql
{created.by="J"}
```

Or:

```traceql
{built.with="Java"}
```

Or combine both:

```traceql
{created.by="J" && built.with="Java"}
```

### Method 3: Search by Service
```traceql
{service.name="gateway" && created.by="J"}
```

## What You'll See

When you click on the trace in Grafana, you'll see:

1. **Trace Timeline**: Visual representation of the entire request flow through all 6 services
2. **Span Details**: Each service's processing time
3. **Resource Attributes** (Metadata):
   - `created.by`: J
   - `built.with`: Java
   - `service.name`: [service name]
   - `service.version`: 1.0.0
   - `deployment.environment`: development
   - `service.namespace`: microservices
   - `telemetry.sdk.language`: java
   - And more...

## Generating More Sample Traces

Run this command to generate new traces with custom metadata:

```bash
for i in {1..5}; do
  curl -s "http://localhost:8080/api/chain?data=test-$i"
  echo ""
  sleep 1
done
```

Then search in Grafana using `{created.by="J"}` to find your new traces!

## API Verification

You can also verify via Tempo's API:

```bash
# Search for traces with created.by=J
curl -s "http://localhost:3200/api/search?tags=created.by%3DJ" | python3 -m json.tool

# Search for traces with built.with=Java
curl -s "http://localhost:3200/api/search?tags=built.with%3DJava" | python3 -m json.tool
```

## Implementation Files

The custom metadata was added in:
- `/src/main/resources/application.yml` - Configuration
- `/src/main/java/dev/siegfred/tel/config/OpenTelemetryConfig.java` - Java config
- `/docker-compose-microservices.yml` - Environment variables for all services
