# Complete Guide: Correlating Traces with Metrics in Grafana

This guide shows you exactly how to see CPU usage, memory, and other metrics related to a specific trace.

## Available Metrics

Your application now exposes these metrics to Prometheus:

### HTTP Request Metrics
- `http_server_requests_seconds_count` - Total request count
- `http_server_requests_seconds_sum` - Total request duration
- `http_server_requests_seconds_bucket` - Request duration histogram

### JVM Memory Metrics
- `jvm_memory_used_bytes` - Memory usage by area (heap/nonheap)
- `jvm_memory_max_bytes` - Maximum available memory
- `jvm_memory_committed_bytes` - Committed memory

### CPU Metrics
- `process_cpu_usage` - JVM process CPU usage (0-1)
- `system_cpu_usage` - Overall system CPU usage (0-1)
- `system_cpu_count` - Number of CPU cores

### Thread Metrics
- `jvm_threads_live` - Current live threads
- `jvm_threads_peak` - Peak thread count
- `jvm_threads_states` - Thread states (runnable, blocked, waiting)

### GC Metrics
- `jvm_gc_pause_seconds_count` - GC pause count
- `jvm_gc_pause_seconds_sum` - Total GC pause time
- `jvm_gc_memory_allocated_bytes_total` - Memory allocated
- `jvm_gc_memory_promoted_bytes_total` - Memory promoted to old gen

## Example: Trace-to-Metrics Workflow

### Test Data
**Trace ID:** `d02c1e12b55364c2fd01820a8dc6af92`
**Timestamp:** `2025-10-18T18:27:41+08:00`
**Service:** `tel`
**Endpoint:** `/api/greet`

---

## Method 1: Manual Time-Based Correlation (Most Reliable)

### Step 1: Find Your Trace in Tempo

1. Open **Grafana**: http://localhost:3000
2. Go to **Explore** (compass icon)
3. Select **Tempo** datasource
4. Search for your trace: `d02c1e12b55364c2fd01820a8dc6af92`
5. Click on the trace to view details
6. **Note the exact timestamp** from the trace (top of the view)

### Step 2: View Logs for Context

1. While viewing the trace, click **"Logs for this span"**
2. Grafana switches to Loki and shows:
   ```
   Received greet request for name: MetricsCorrelationDemo
   Successfully processed greeting for MetricsCorrelationDemo
   ```
3. This confirms the trace context

### Step 3: Correlate with CPU Usage

1. Open a **new Explore tab** (+ icon)
2. Select **Prometheus** datasource
3. Set the time range to match your trace timestamp:
   - Click time picker (top right)
   - Set: `2025-10-18 18:27:30` to `2025-10-18 18:27:50` (±10s around trace)
4. Query for CPU usage:
   ```promql
   process_cpu_usage{application="tel"}
   ```
5. Click **Run query**
6. You'll see CPU usage at the time your trace occurred

### Step 4: View Memory Usage

In the same Prometheus view, add another query:
```promql
jvm_memory_used_bytes{application="tel", area="heap"}
```

You'll see heap memory usage during the trace.

### Step 5: View Request Latency

Query for request duration:
```promql
rate(http_server_requests_seconds_sum{uri="/api/greet"}[30s]) /
rate(http_server_requests_seconds_count{uri="/api/greet"}[30s])
```

This shows average request duration at that time.

---

## Method 2: Using Grafana Dashboard (Recommended for Regular Use)

### Create a Correlation Dashboard

1. In Grafana, create a new dashboard
2. Add multiple panels synchronized by time

#### Panel 1: Request Rate
```promql
rate(http_server_requests_seconds_count{application="tel"}[1m])
```

#### Panel 2: CPU Usage
```promql
process_cpu_usage{application="tel"}
```

#### Panel 3: Memory Usage
```promql
jvm_memory_used_bytes{application="tel", area="heap"}
```

#### Panel 4: Active Threads
```promql
jvm_threads_live{application="tel"}
```

### How to Use the Dashboard

1. Note your **trace timestamp** from Tempo
2. Open the dashboard
3. Click and drag on any graph to zoom to the trace time
4. All panels zoom together (time-synchronized)
5. See CPU, memory, threads at the exact moment of your trace

---

## Method 3: Quick Navigation Workflow

### From Trace → Metrics (Step by Step)

**Starting point:** You have a trace ID from an alert or investigation

1. **Open Tempo**
   - Grafana → Explore → Tempo
   - Search trace: `d02c1e12b55364c2fd01820a8dc6af92`

2. **Get Context from Logs**
   - Click "Logs for this span"
   - Read what the request was doing
   - Note: timestamp, endpoint, parameters

3. **Switch to Metrics**
   - Open new Explore tab
   - Select Prometheus datasource
   - Set time range to match trace timestamp

4. **Query Relevant Metrics**

   **For CPU analysis:**
   ```promql
   process_cpu_usage{application="tel"}
   ```

   **For memory spikes:**
   ```promql
   sum(jvm_memory_used_bytes{application="tel"}) by (area)
   ```

   **For GC pressure:**
   ```promql
   rate(jvm_gc_pause_seconds_count{application="tel"}[1m])
   ```

   **For thread issues:**
   ```promql
   jvm_threads_states{application="tel"}
   ```

5. **Analyze Correlation**
   - Did CPU spike during the slow trace?
   - Was memory high?
   - Were there GC pauses?
   - Were threads blocked?

---

## Practical Troubleshooting Scenarios

### Scenario 1: Slow Request Investigation

**Problem:** You have a slow trace (500ms response time)

**Steps:**
1. Find trace in Tempo → note timestamp `T`
2. View logs → see what endpoint and operation
3. Check Prometheus at time `T`:
   ```promql
   # CPU at that moment
   process_cpu_usage{application="tel"}

   # Memory pressure
   rate(jvm_gc_pause_seconds_sum{application="tel"}[1m])

   # Thread count
   jvm_threads_live{application="tel"}
   ```

**Findings you might discover:**
- High CPU → Computational bottleneck
- High GC time → Memory pressure
- Many threads → Possible contention
- Normal metrics → Likely external dependency (database, API)

### Scenario 2: Memory Leak Detection

**Problem:** Service crashed with OOM, you have traces before the crash

**Steps:**
1. Find traces in the hour before crash
2. Note timestamps of multiple traces
3. Query memory trends:
   ```promql
   jvm_memory_used_bytes{application="tel", area="heap"}
   ```
4. Overlay request rate:
   ```promql
   rate(http_server_requests_seconds_count{application="tel"}[5m])
   ```

**Analysis:**
- Is memory growing independent of request rate? → Memory leak
- Memory grows with requests but doesn't drop? → No cleanup
- Memory stable? → Sudden spike caused crash

### Scenario 3: GC Pause Correlation

**Problem:** Users report intermittent latency

**Steps:**
1. Find a slow trace (e.g., 2s response when normally 50ms)
2. Get exact timestamp
3. Check GC activity:
   ```promql
   rate(jvm_gc_pause_seconds_count{application="tel"}[1m])
   ```
4. Check memory before GC:
   ```promql
   jvm_memory_used_bytes{application="tel", area="heap"}
   ```

**Correlation:**
- GC pause spike at trace time? → GC caused latency
- Memory at limit before GC? → Increase heap size
- Frequent GC? → Reduce allocation rate

---

## Creating Alerts Based on Traces

### Alert on High Latency with Metrics

In Prometheus/Grafana, create an alert:

```promql
# Alert if P95 latency > 500ms AND CPU > 80%
(
  histogram_quantile(0.95,
    rate(http_server_requests_seconds_bucket{uri="/api/greet"}[5m])
  ) > 0.5
)
AND
(
  process_cpu_usage{application="tel"} > 0.8
)
```

When this fires:
1. Alert includes timestamp
2. Find traces at that time in Tempo
3. Check logs for those traces
4. Metrics already show the issue (high CPU)

---

## Advanced: Building Custom Correlation Queries

### Query: Find All Traces During High CPU

1. In Prometheus, find high CPU period:
   ```promql
   process_cpu_usage{application="tel"} > 0.7
   ```
   Note the time range: e.g., `10:15 - 10:18`

2. In Tempo, search for traces in that range:
   - Select time range: `10:15 - 10:18`
   - Query: `{service.name="tel"}`
   - Sort by duration
   - Investigate the slowest traces

### Query: Memory Usage During Specific Trace Pattern

If you notice a pattern (e.g., requests with parameter X are slow):

1. Find multiple traces with that pattern in Tempo
2. Note all timestamps
3. Query memory at those specific times:
   ```promql
   jvm_memory_used_bytes{application="tel"}
   ```
4. Check if memory is consistently high for that pattern

---

## Tips for Effective Correlation

### 1. Always Check Time Precision
- Trace timestamps are nanosecond precision
- Prometheus metrics are typically 15s intervals
- Allow ±30s window when correlating

### 2. Use Multiple Metrics
Don't rely on one metric:
- CPU alone doesn't tell the full story
- Check CPU + Memory + GC + Threads
- Cross-reference with logs

### 3. Establish Baselines
Before investigating traces:
```promql
# Normal CPU
avg_over_time(process_cpu_usage{application="tel"}[1h])

# Normal memory
avg_over_time(jvm_memory_used_bytes{application="tel"}[1h])
```

Compare trace-time metrics to baseline.

### 4. Use Grafana Variables

Create a dashboard with variables:
- `$trace_time` - Set from trace timestamp
- `$window` - Time window around trace (default: 5m)

Query:
```promql
process_cpu_usage{application="tel"}[${window}:${trace_time}]
```

---

## Common Metric Queries for Trace Correlation

### CPU Usage Around Trace Time
```promql
process_cpu_usage{application="tel"}
```

### Memory at Trace Time
```promql
sum(jvm_memory_used_bytes{application="tel"}) by (area)
```

### GC Activity During Trace
```promql
rate(jvm_gc_pause_seconds_count{application="tel"}[1m])
```

### Request Rate Context
```promql
rate(http_server_requests_seconds_count{application="tel"}[1m])
```

### Thread Contention
```promql
jvm_threads_states{application="tel", state="blocked"}
```

### Request Duration P95
```promql
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{uri="/api/greet"}[5m])
)
```

---

## Complete Investigation Example

### Given: Slow Trace
- **Trace ID:** `d02c1e12b55364c2fd01820a8dc6af92`
- **Duration:** 87ms (normal is 10ms)
- **Timestamp:** `2025-10-18T18:27:41+08:00`

### Investigation Steps:

1. **View Trace in Tempo**
   - See span breakdown
   - Check if time spent in app or external call

2. **Check Logs**
   - Click "Logs for this span"
   - See: "Received greet request" → "Successfully processed"
   - No errors, time spent in processing

3. **Check Metrics at 18:27:41**

   **CPU:**
   ```promql
   process_cpu_usage{application="tel"}
   ```
   Result: 45% (normal)

   **Memory:**
   ```promql
   jvm_memory_used_bytes{application="tel", area="heap"}
   ```
   Result: 120MB / 512MB (normal)

   **GC:**
   ```promql
   rate(jvm_gc_pause_seconds_count{application="tel"}[1m])
   ```
   Result: 0.5 pauses/min (normal)

   **Request Rate:**
   ```promql
   rate(http_server_requests_seconds_count{application="tel"}[1m])
   ```
   Result: 50 req/min (spike from normal 10 req/min!)

4. **Conclusion:**
   - Latency due to increased load (50 req/min vs normal 10)
   - Not a resource issue
   - Application handling load correctly
   - Solution: Add capacity or optimize for higher throughput

---

## Summary

### What You Can Do Now:

✅ **Find a trace** → Note timestamp
✅ **View related logs** → Understand context
✅ **Query metrics at trace time** → See CPU, memory, GC
✅ **Correlate patterns** → High CPU = slow traces?
✅ **Build dashboards** → Time-synchronized view
✅ **Set up alerts** → Trigger on metric + trace patterns

### Quick Reference:

| Metric | Query | What it Shows |
|--------|-------|---------------|
| CPU | `process_cpu_usage{application="tel"}` | JVM CPU usage |
| Memory | `jvm_memory_used_bytes{application="tel"}` | Heap/non-heap memory |
| GC | `rate(jvm_gc_pause_seconds_count[1m])` | GC frequency |
| Latency | `rate(http_server_requests_seconds_sum[1m])/rate(http_server_requests_seconds_count[1m])` | Avg response time |
| Threads | `jvm_threads_live{application="tel"}` | Active threads |

---

## Next Steps

1. **Practice**: Generate some requests, find traces, check metrics
2. **Create Dashboard**: Build your first correlation dashboard
3. **Set Alerts**: Alert on latency + high CPU conditions
4. **Document Patterns**: Keep a runbook of trace→metric correlations you discover

Your observability stack now provides **complete visibility**: traces show *what happened*, logs show *why*, and metrics show *how the system behaved*.
