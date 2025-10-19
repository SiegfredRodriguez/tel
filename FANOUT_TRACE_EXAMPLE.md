# RabbitMQ Fan-Out Trace Example

## What is a Fan-Out Pattern?

A **fan-out** pattern is when **one message is broadcast to multiple consumers in parallel**.

### Architecture:
```
Publisher (gateway)
    |
    | sends 1 message
    ↓
[Fanout Exchange: tel.fanout.exchange]
    |
    ├───────────┬───────────┐
    ↓           ↓           ↓
Queue A     Queue B     Queue C
    ↓           ↓           ↓
Consumer-A  Consumer-B  Consumer-C
(parallel)  (parallel)  (parallel)
```

## Sample Trace ID

**Trace ID:** `b34d20ebac4e6740288239016a738757`

### What Happened:
1. **Gateway** published message with `data=fanout-test-3` to fanout exchange
2. **RabbitMQ** fanout exchange automatically copied the message to **3 queues**
3. **Service-E** has **3 consumers** (Consumer-A, B, C) listening to each queue
4. All **3 consumers received and processed the SAME message in PARALLEL**
5. **All operations share the SAME trace ID** but have different span IDs

## Trace Structure

```
Trace: b34d20ebac4e6740288239016a738757
│
├─ gateway (Publisher)
│   └─ POST /api/fanout
│       └─ tel.fanout.exchange publish [Parent Span]
│            │
│            ├─────────────┬───────────────┐
│            │             │               │
│            ↓             ↓               ↓
│
├─ service-e (Consumer-A)
│   └─ tel.fanout.queue.a receive
│       ├─ consumer.name: Consumer-A
│       ├─ messaging.pattern: fan-out
│       ├─ messaging.consumer.type: parallel
│       └─ message.data: fanout-test-3
│
├─ service-e (Consumer-B)
│   └─ tel.fanout.queue.b receive
│       ├─ consumer.name: Consumer-B
│       ├─ messaging.pattern: fan-out
│       ├─ messaging.consumer.type: parallel
│       └─ message.data: fanout-test-3
│
└─ service-e (Consumer-C)
    └─ tel.fanout.queue.c receive
        ├─ consumer.name: Consumer-C
        ├─ messaging.pattern: fan-out
        ├─ messaging.consumer.type: parallel
        └─ message.data: fanout-test-3
```

## Key Characteristics in the Trace

### 1. Same Trace ID
All spans share **the same trace ID**: `b34d20ebac4e6740288239016a738757`

This means they're all part of the same distributed transaction!

### 2. Different Span IDs
Each consumer has its own span ID:
- Consumer-A: `f2be22935936f123`
- Consumer-B: `a6e943e7437a53e0`
- Consumer-C: `845c1d33232f39a5`

### 3. Parallel Execution Timing
Looking at the logs:
```
12:50:30.184112337Z - Consumer-B received
12:50:30.184294504Z - Consumer-C received
12:50:30.184796837Z - Consumer-A received
```
**All received within 0.6 milliseconds!** This shows parallel processing.

### 4. Custom Span Attributes
Each consumer span includes:
- `consumer.name`: Identifies which consumer (A, B, or C)
- `messaging.pattern`: "fan-out"
- `messaging.consumer.type`: "parallel"
- `message.data`: "fanout-test-3"
- `message.source`: "gateway"
- `created.by`: "J" (Resource Attribute)
- `built.with`: "Java" (Resource Attribute)

## What You See in Grafana

When you open this trace in Grafana (http://localhost:3000):

1. **Trace Timeline View:**
   ```
   gateway: POST /api/fanout ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            │
            ├─ publish to exchange ━━━━┓
            │                           ┣━━ Consumer-A ━━━━
            │                           ┣━━ Consumer-B ━━━━
            │                           ┗━━ Consumer-C ━━━━

            ^                           ^
            |                           |
        Publisher                   3 parallel consumers
   ```

2. **Service Graph:**
   ```
   gateway ──→ RabbitMQ Exchange ──┬──→ service-e (Consumer-A)
                                   ├──→ service-e (Consumer-B)
                                   └──→ service-e (Consumer-C)
   ```

3. **Span List:**
   - 1 parent span (gateway publish)
   - 3 child spans (consumer receives) all at the same level

## Comparing: Fan-Out vs Chain

### Chain Pattern (Sequential):
```
gateway ──→ service-a ──→ service-b ──→ service-c
  (1)         (2)           (3)           (4)

Timeline: ━━━━━━━━━━━━━━━━━━━━━━━━→
           One after another
```

### Fan-Out Pattern (Parallel):
```
gateway ──→ [Exchange]
              ├──→ Consumer-A
              ├──→ Consumer-B
              └──→ Consumer-C

Timeline: ━━━┳━━━━━━━
           ━━━┫━━━━━━━  All at same time!
           ━━━┻━━━━━━━
```

## How to View This Trace

### In Grafana:
1. Open: http://localhost:3000
2. Go to: Explore → Tempo
3. Search: `b34d20ebac4e6740288239016a738757`
4. Or query: `{messaging.pattern="fan-out"}`

### Via API:
```bash
# Get the trace
curl -s "http://localhost:3200/api/traces/b34d20ebac4e6740288239016a738757" | python3 -m json.tool

# Search for all fan-out traces
curl -s "http://localhost:3200/api/search?tags=messaging.pattern%3Dfan-out"
```

## Generate More Fan-Out Traces

```bash
# Send a fan-out request
curl "http://localhost:8080/api/fanout?data=my-test"

# Generate multiple
for i in {1..5}; do
  curl -s "http://localhost:8080/api/fanout?data=test-$i"
  sleep 1
done
```

## What Makes This Different?

### Resource Attributes (same for all spans):
- `created.by`: J
- `built.with`: Java
- `service.name`: gateway or service-e
- `service.version`: 1.0.0

### Span Attributes (different per consumer):
- `consumer.name`: Consumer-A / Consumer-B / Consumer-C
- `messaging.destination.name`: Different queue for each
- `message.data`: Same data for all (fanout-test-3)

This combination shows:
- **Who built it**: Resource Attributes (J, Java)
- **What happened**: Span Attributes (which consumer, which queue)

## Fan-Out Benefits

1. **Parallel Processing**: All consumers work simultaneously
2. **Broadcast**: Same message delivered to multiple destinations
3. **Trace Propagation**: All consumers see the same trace context
4. **Visibility**: Single trace shows entire fan-out operation

## Use Cases

- Notification systems (send to email, SMS, push notification)
- Event broadcasting (one event, multiple handlers)
- Replication (write to multiple data stores)
- Parallel data processing pipelines
