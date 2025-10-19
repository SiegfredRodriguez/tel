# Quick Start Guide

This guide will help you build, start, and explore the OpenTelemetry demo application.

## Prerequisites

- Java 17 or higher
- Docker and Docker Compose
- Bash shell

## Scripts Overview

### 1. `start.sh` - Build and Start Everything

This script will:
- ✅ Check prerequisites (Java, Docker)
- ✅ Clean up old containers
- ✅ Build the application with Gradle
- ✅ Start all infrastructure (Tempo, Grafana, RabbitMQ, etc.)
- ✅ Start all microservices (gateway, service-a through service-e)
- ✅ Verify all services are healthy
- ✅ Display service URLs and next steps

**Usage:**
```bash
./start.sh
```

**Expected Output:**
```
=========================================================================
                   OpenTelemetry Demo - Startup Script
=========================================================================

>>> Checking Prerequisites

✓ Java found: 17.0.x
✓ Docker found: 24.x.x
✓ Docker Compose found: 2.x.x

>>> Cleaning Up Old Containers

✓ Cleanup complete

>>> Building Application

✓ Application built successfully

>>> Starting Infrastructure Services

✓ Infrastructure started

>>> Starting Microservices

✓ All services started

>>> Checking Service Health

✓ gateway is healthy
✓ service-a is healthy
✓ service-b is healthy
✓ service-c is healthy
✓ service-d is healthy
✓ service-e is healthy
✓ Grafana is ready
✓ Tempo is ready

=========================================================================
                          STARTUP COMPLETE
=========================================================================

Service URLs:
  • Gateway (API):        http://localhost:8080
  • Grafana (UI):         http://localhost:3000
  • Prometheus (Metrics): http://localhost:9090
  • RabbitMQ (UI):        http://localhost:15672 (guest/guest)
```

**Time:** ~2-3 minutes (first run may take longer due to Docker image downloads)

---

### 2. `demo.sh` - Generate Traces and Show Examples

This script will:
- ✅ Generate 3 chain pattern traces (sequential)
- ✅ Generate 3 fan-out pattern traces (parallel)
- ✅ Fetch trace IDs from Tempo
- ✅ Display trace IDs with direct Grafana links
- ✅ Show API commands to view traces
- ✅ Provide comparison of both patterns

**Usage:**
```bash
./demo.sh
```

**Expected Output:**
```
=========================================================================
              OpenTelemetry Demo - Trace Examples
=========================================================================

╔═══════════════════════════════════════════════════════════════════╗
║ PART 1: Microservices Chain Pattern (Sequential)
╚═══════════════════════════════════════════════════════════════════╝

Pattern: gateway → service-a → service-b → service-c → service-d → service-e
Type:    Sequential (one after another)

✓ Chain request 1 completed
✓ Chain request 2 completed
✓ Chain request 3 completed

╔═══════════════════════════════════════════════════════════════════╗
║ PART 2: RabbitMQ Fan-Out Pattern (Parallel)
╚═══════════════════════════════════════════════════════════════════╝

Pattern: gateway → [fanout exchange] → Consumer-A + Consumer-B + Consumer-C
Type:    Parallel (all consumers receive same message simultaneously)

✓ Fan-out request 1 completed
✓ Fan-out request 2 completed
✓ Fan-out request 3 completed

╔═══════════════════════════════════════════════════════════════════╗
║ TRACE SUMMARY
╚═══════════════════════════════════════════════════════════════════╝

Custom Metadata Applied to All Traces:
  • created.by: J
  • built.with: Java
  • service.version: 1.0.0

═══════════════════════════════════════════════════════════════════
CHAIN PATTERN TRACES (Sequential)
═══════════════════════════════════════════════════════════════════

Chain Trace 1:
🔍 Trace ID: abc123def456...
  View in Grafana: http://localhost:3000/explore?...
  View via API: curl "http://localhost:3200/api/traces/abc123..." | jq .
  Services: gateway → service-a → service-b → service-c → service-d → service-e

═══════════════════════════════════════════════════════════════════
FAN-OUT PATTERN TRACES (Parallel)
═══════════════════════════════════════════════════════════════════

Fan-Out Trace 1:
🔍 Trace ID: xyz789uvw012...
  View in Grafana: http://localhost:3000/explore?...
  View via API: curl "http://localhost:3200/api/traces/xyz789..." | jq .
  Pattern: 1 publisher → 3 parallel consumers
```

**Time:** ~30 seconds

---

## Step-by-Step Walkthrough

### Step 1: Start Everything

```bash
./start.sh
```

Wait for the script to complete. You should see "STARTUP COMPLETE" with all services marked as healthy.

### Step 2: Run the Demo

```bash
./demo.sh
```

This will generate sample traces and display the trace IDs.

### Step 3: View Traces in Grafana

1. Open http://localhost:3000 in your browser
2. Click the **Explore** icon (compass) on the left sidebar
3. Select **Tempo** as the datasource
4. Use one of these methods to find traces:

   **Method A: Paste a Trace ID**
   - Copy a trace ID from the demo.sh output
   - Paste it into the search box
   - Press Enter

   **Method B: Use TraceQL Queries**
   ```
   {created.by="J"}                    # All traces by creator J
   {built.with="Java"}                 # All Java traces
   {messaging.pattern="fan-out"}       # Fan-out pattern only
   {service.name="gateway"}            # All gateway traces
   ```

5. Click on any trace to see:
   - **Timeline view**: Visual representation of the trace
   - **Service graph**: How services are connected
   - **Span details**: Click on any span to see:
     - Resource Attributes: created.by, built.with, service.version
     - Span Attributes: consumer.name, message.data, etc.

---

## Understanding the Patterns

### Chain Pattern (Sequential)

**Flow:**
```
gateway ──→ service-a ──→ service-b ──→ service-c ──→ service-d ──→ service-e
```

**Trace Structure:**
```
Root Span (gateway)
  └─ Child Span (service-a)
      └─ Child Span (service-b)
          └─ Child Span (service-c)
              └─ Child Span (service-d)
                  └─ Child Span (service-e)
```

**In Grafana Timeline:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━→
Services execute one after another
```

### Fan-Out Pattern (Parallel)

**Flow:**
```
                      ┌──→ Consumer-A (Queue A)
gateway ──→ Exchange ─┼──→ Consumer-B (Queue B)
                      └──→ Consumer-C (Queue C)
```

**Trace Structure:**
```
Root Span (gateway)
  └─ Child Span (exchange publish)
      ├─ Child Span (Consumer-A receive)
      ├─ Child Span (Consumer-B receive)
      └─ Child Span (Consumer-C receive)
```

**In Grafana Timeline:**
```
gateway ━━━━━━┓
              ┗━━ publish ━━┓
                            ┣━━━━━━━━━━━━━ Consumer-A
                            ┣━━━━━━━━━━━━━ Consumer-B
                            ┗━━━━━━━━━━━━━ Consumer-C

All 3 consumers execute in parallel!
```

---

## Custom Metadata

All traces include these **Resource Attributes**:
- `created.by`: J
- `built.with`: Java
- `service.version`: 1.0.0

These appear in every span from every service, allowing you to:
- Filter traces by creator
- Filter traces by technology
- Group and analyze by version

---

## Manual Testing

### Chain Request
```bash
curl "http://localhost:8080/api/chain?data=my-test"
```

### Fan-Out Request
```bash
curl "http://localhost:8080/api/fanout?data=my-test"
```

### Generate Multiple Traces
```bash
# Generate 10 chain traces
for i in {1..10}; do
  curl -s "http://localhost:8080/api/chain?data=test-$i"
  sleep 1
done

# Generate 10 fan-out traces
for i in {1..10}; do
  curl -s "http://localhost:8080/api/fanout?data=test-$i"
  sleep 1
done
```

---

## API Access

### Search Traces
```bash
# By creator
curl "http://localhost:3200/api/search?tags=created.by%3DJ" | jq .

# By pattern
curl "http://localhost:3200/api/search?tags=messaging.pattern%3Dfan-out" | jq .

# By service
curl "http://localhost:3200/api/search?tags=service.name%3Dgateway" | jq .
```

### Get Specific Trace
```bash
curl "http://localhost:3200/api/traces/TRACE_ID_HERE" | jq .
```

---

## Service URLs

| Service | URL | Description |
|---------|-----|-------------|
| Gateway | http://localhost:8080 | Main API entry point |
| Grafana | http://localhost:3000 | Trace visualization |
| Prometheus | http://localhost:9090 | Metrics |
| RabbitMQ UI | http://localhost:15672 | Message broker (guest/guest) |
| Tempo API | http://localhost:3200 | Trace storage API |

---

## Troubleshooting

### Services Not Starting
```bash
# Check Docker is running
docker ps

# Check logs for specific service
docker logs tel-gateway
docker logs tel-service-e

# Restart everything
docker-compose -f docker-compose-microservices.yml restart
```

### Traces Not Appearing
```bash
# Wait a few seconds for indexing (Tempo needs 10-15 seconds)
sleep 10

# Check Tempo is running
curl http://localhost:3200/ready

# Check gateway is sending traces
docker logs tel-gateway | grep -i "trace"

# Verify traces exist by searching all gateway traces
curl "http://localhost:3200/api/search?tags=service.name%3Dgateway&limit=10" | python3 -m json.tool
```

**Technical Note:** The demo.sh script searches traces using **resource attributes** (like `service.name=gateway`) rather than **span attributes** (like `messaging.pattern=fan-out`) because:
- Resource attributes are always indexed by Tempo for tag-based searches
- Span attributes may not be immediately available for tag searches
- Both scripts filter by `rootTraceName` after fetching to distinguish chain vs fan-out patterns

### Port Already in Use
```bash
# Find process using port
lsof -i :8080

# Kill the process or change port in docker-compose
```

---

## Stopping Everything

```bash
# Stop all containers
docker-compose -f docker-compose-microservices.yml down

# Stop and remove volumes (clean slate)
docker-compose -f docker-compose-microservices.yml down -v
```

---

## Next Steps

1. ✅ Run `./start.sh` to start everything
2. ✅ Run `./demo.sh` to generate sample traces
3. ✅ Open Grafana at http://localhost:3000
4. ✅ Explore traces using the trace IDs from demo.sh
5. ✅ Try searching with TraceQL: `{created.by="J"}`
6. ✅ Generate your own traces with curl commands
7. ✅ Check out the detailed documentation:
   - `CUSTOM_METADATA_SAMPLE.md` - Custom metadata guide
   - `FANOUT_TRACE_EXAMPLE.md` - Fan-out pattern details
   - `README.md` - Full project documentation

---

## Summary

```bash
# 1. Start everything (takes 2-3 minutes)
./start.sh

# 2. Generate sample traces (takes 30 seconds)
./demo.sh

# 3. Open Grafana
open http://localhost:3000

# 4. Have fun exploring! 🎉
```
