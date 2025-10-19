#!/bin/bash

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

echo -e "${CYAN}=========================================================================${NC}"
echo -e "${CYAN}              OpenTelemetry Demo - Trace Examples${NC}"
echo -e "${CYAN}=========================================================================${NC}"
echo ""

# Function to print section headers
print_header() {
    echo -e "\n${BLUE}╔═══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC} $1"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════════╝${NC}\n"
}

# Function to print success messages
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

# Function to print info messages
print_info() {
    echo -e "${YELLOW}➜ $1${NC}"
}

# Function to print trace info
print_trace() {
    echo -e "${MAGENTA}🔍 $1${NC}"
}

# Arrays to store trace IDs
declare -a CHAIN_TRACE_IDS
declare -a FANOUT_TRACE_IDS

# Check if services are running
print_info "Checking if services are running..."
if ! curl -s -f "http://localhost:8080/actuator/health" > /dev/null 2>&1; then
    echo -e "${RED}✗ Gateway is not running! Please run ./start.sh first${NC}"
    exit 1
fi
print_success "Services are running"
echo ""

#==============================================================================
# PART 1: MICROSERVICES CHAIN PATTERN
#==============================================================================

print_header "PART 1: Microservices Chain Pattern (Sequential)"

echo -e "${CYAN}Pattern:${NC} gateway → service-a → service-b → service-c → service-d → service-e"
echo -e "${CYAN}Type:${NC}    Sequential (one after another)"
echo ""

print_info "Generating 3 chain requests..."
echo ""

for i in {1..3}; do
    echo -e "${YELLOW}Request $i:${NC} Sending chain request..."

    RESPONSE=$(curl -s "http://localhost:8080/api/chain?data=demo-chain-$i")

    if [ $? -eq 0 ]; then
        print_success "Chain request $i completed"
        echo -e "   Response: $(echo $RESPONSE | python3 -m json.tool 2>/dev/null | head -3 | tail -1)"
    else
        echo -e "${RED}✗ Request failed${NC}"
    fi

    sleep 2
done

print_info "Waiting for traces to be indexed (10 seconds)..."
sleep 10

# Fetch chain trace IDs
print_info "Fetching chain trace IDs from Tempo..."
CHAIN_TRACES=$(curl -s "http://localhost:3200/api/search?tags=service.name%3Dgateway&limit=100" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    traces = data.get('traces', [])
    chain_traces = [t for t in traces if 'chain' in t.get('rootTraceName', '').lower()]
    for trace in chain_traces[:3]:
        print(trace['traceID'])
except:
    pass
" 2>/dev/null)

if [ -n "$CHAIN_TRACES" ]; then
    # Convert to array (compatible with bash 3.x on macOS)
    IFS=$'\n' read -d '' -r -a CHAIN_TRACE_IDS <<< "$CHAIN_TRACES"
    print_success "Found ${#CHAIN_TRACE_IDS[@]} chain traces"
else
    print_info "No chain traces found yet (this is okay, continuing...)"
fi

echo ""

#==============================================================================
# PART 2: FAN-OUT PATTERN
#==============================================================================

print_header "PART 2: RabbitMQ Fan-Out Pattern (Parallel)"

echo -e "${CYAN}Pattern:${NC} gateway → [fanout exchange] → Consumer-A + Consumer-B + Consumer-C"
echo -e "${CYAN}Type:${NC}    Parallel (all consumers receive same message simultaneously)"
echo ""

print_info "Generating 3 fan-out requests..."
echo ""

for i in {1..3}; do
    echo -e "${YELLOW}Request $i:${NC} Sending fan-out request..."

    RESPONSE=$(curl -s "http://localhost:8080/api/fanout?data=demo-fanout-$i")

    if [ $? -eq 0 ]; then
        print_success "Fan-out request $i completed"
        STATUS=$(echo $RESPONSE | python3 -c "import sys, json; print(json.load(sys.stdin).get('status', 'N/A'))" 2>/dev/null)
        echo -e "   Status: $STATUS"
    else
        echo -e "${RED}✗ Request failed${NC}"
    fi

    sleep 2
done

print_info "Waiting for traces to be indexed (10 seconds)..."
sleep 10

# Fetch fan-out trace IDs
print_info "Fetching fan-out trace IDs from Tempo..."
FANOUT_TRACES=$(curl -s "http://localhost:3200/api/search?tags=service.name%3Dgateway&limit=100" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    traces = data.get('traces', [])
    fanout_traces = [t for t in traces if 'fanout' in t.get('rootTraceName', '').lower()]
    for trace in fanout_traces[:3]:
        print(trace['traceID'])
except:
    pass
" 2>/dev/null)

if [ -n "$FANOUT_TRACES" ]; then
    # Convert to array (compatible with bash 3.x on macOS)
    IFS=$'\n' read -d '' -r -a FANOUT_TRACE_IDS <<< "$FANOUT_TRACES"
    print_success "Found ${#FANOUT_TRACE_IDS[@]} fan-out traces"
else
    print_info "No fan-out traces found yet (this is okay, continuing...)"
fi

echo ""

#==============================================================================
# SUMMARY
#==============================================================================

print_header "TRACE SUMMARY"

echo -e "${GREEN}Custom Metadata Applied to All Traces:${NC}"
echo -e "  • ${CYAN}created.by:${NC} J"
echo -e "  • ${CYAN}built.with:${NC} Java"
echo -e "  • ${CYAN}service.version:${NC} 1.0.0"
echo ""

echo -e "${GREEN}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}CHAIN PATTERN TRACES (Sequential)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════════${NC}"
echo ""

if [ ${#CHAIN_TRACE_IDS[@]} -gt 0 ]; then
    for i in "${!CHAIN_TRACE_IDS[@]}"; do
        TRACE_ID="${CHAIN_TRACE_IDS[$i]}"
        echo -e "${CYAN}Chain Trace $((i+1)):${NC}"
        print_trace "Trace ID: $TRACE_ID"
        echo -e "  ${YELLOW}View in Grafana:${NC}"
        echo -e "    http://localhost:3000/explore?left=%7B%22datasource%22:%22tempo%22,%22queries%22:%5B%7B%22query%22:%22$TRACE_ID%22%7D%5D%7D"
        echo -e "  ${YELLOW}View via API:${NC}"
        echo -e "    curl \"http://localhost:3200/api/traces/$TRACE_ID\" | jq ."
        echo ""

        # Show trace details
        print_info "Trace details:"
        curl -s "http://localhost:3200/api/traces/$TRACE_ID" | python3 << EOF 2>/dev/null
import sys, json
try:
    trace = json.load(sys.stdin)
    services = set()
    for batch in trace.get('batches', []):
        for attr in batch.get('resource', {}).get('attributes', []):
            if attr.get('key') == 'service.name':
                services.add(attr.get('value', {}).get('stringValue', ''))
    if services:
        print(f"    Services: {' → '.join(sorted(services))}")
except:
    pass
EOF
        echo ""
    done
else
    print_info "Run these commands to generate chain traces:"
    echo -e "  ${YELLOW}curl http://localhost:8080/api/chain?data=test1${NC}"
    echo -e "  ${YELLOW}curl http://localhost:8080/api/chain?data=test2${NC}"
    echo ""
    print_info "Then search in Grafana with: {service.name=\"gateway\"}"
    echo ""
fi

echo -e "${GREEN}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}FAN-OUT PATTERN TRACES (Parallel)${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════════${NC}"
echo ""

if [ ${#FANOUT_TRACE_IDS[@]} -gt 0 ]; then
    for i in "${!FANOUT_TRACE_IDS[@]}"; do
        TRACE_ID="${FANOUT_TRACE_IDS[$i]}"
        echo -e "${CYAN}Fan-Out Trace $((i+1)):${NC}"
        print_trace "Trace ID: $TRACE_ID"
        echo -e "  ${YELLOW}View in Grafana:${NC}"
        echo -e "    http://localhost:3000/explore?left=%7B%22datasource%22:%22tempo%22,%22queries%22:%5B%7B%22query%22:%22$TRACE_ID%22%7D%5D%7D"
        echo -e "  ${YELLOW}View via API:${NC}"
        echo -e "    curl \"http://localhost:3200/api/traces/$TRACE_ID\" | jq ."
        echo ""

        # Show trace details
        print_info "Trace details:"
        curl -s "http://localhost:3200/api/traces/$TRACE_ID" | python3 << EOF 2>/dev/null
import sys, json
try:
    trace = json.load(sys.stdin)
    consumer_count = 0
    for batch in trace.get('batches', []):
        for scope in batch.get('scopeSpans', []):
            for span in scope.get('spans', []):
                for attr in span.get('attributes', []):
                    if attr.get('key') == 'consumer.name':
                        consumer_count += 1
    if consumer_count > 0:
        print(f"    Pattern: 1 publisher → {consumer_count} parallel consumers")
        print(f"    Consumers: Consumer-A, Consumer-B, Consumer-C")
except:
    pass
EOF
        echo ""
    done
else
    print_info "Run these commands to generate fan-out traces:"
    echo -e "  ${YELLOW}curl http://localhost:8080/api/fanout?data=test1${NC}"
    echo -e "  ${YELLOW}curl http://localhost:8080/api/fanout?data=test2${NC}"
    echo ""
    print_info "Then search in Grafana with: {messaging.pattern=\"fan-out\"}"
    echo ""
fi

#==============================================================================
# HOW TO VIEW IN GRAFANA
#==============================================================================

print_header "HOW TO VIEW IN GRAFANA"

echo -e "${GREEN}1. Open Grafana:${NC}"
echo -e "   http://localhost:3000"
echo ""

echo -e "${GREEN}2. Navigate to Explore:${NC}"
echo -e "   • Click the compass icon on the left sidebar"
echo -e "   • Select 'Tempo' as the datasource"
echo ""

echo -e "${GREEN}3. Search for traces:${NC}"
echo -e "   ${CYAN}By Trace ID:${NC}"
echo -e "     Paste any trace ID from above"
echo ""
echo -e "   ${CYAN}By Tags (TraceQL queries):${NC}"
echo -e "     • Chain pattern:    ${YELLOW}{service.name=\"gateway\"}${NC}"
echo -e "     • Fan-out pattern:  ${YELLOW}{messaging.pattern=\"fan-out\"}${NC}"
echo -e "     • By creator:       ${YELLOW}{created.by=\"J\"}${NC}"
echo -e "     • By language:      ${YELLOW}{built.with=\"Java\"}${NC}"
echo ""

echo -e "${GREEN}4. Explore the trace:${NC}"
echo -e "   • Click on any trace to see the timeline"
echo -e "   • Expand spans to see details"
echo -e "   • Look for 'Resource Attributes' section for custom metadata"
echo -e "   • Look for 'Span Attributes' section for operation-specific data"
echo ""

#==============================================================================
# COMPARING PATTERNS
#==============================================================================

print_header "COMPARING THE PATTERNS"

echo -e "${CYAN}CHAIN PATTERN (Sequential):${NC}"
echo -e "  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐"
echo -e "  │ Gateway │───▶│Service-A│───▶│Service-B│───▶│Service-C│ ..."
echo -e "  └─────────┘    └─────────┘    └─────────┘    └─────────┘"
echo -e "  ${YELLOW}Timeline:${NC} ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━→"
echo -e "            One service after another"
echo ""

echo -e "${CYAN}FAN-OUT PATTERN (Parallel):${NC}"
echo -e "                        ┌──▶ Consumer-A"
echo -e "  ┌─────────┐    ┌──────┴──▶ Consumer-B"
echo -e "  │ Gateway │───▶│ Exchange└──▶ Consumer-C"
echo -e "  └─────────┘    └─────────────────────────┘"
echo -e "  ${YELLOW}Timeline:${NC} ━━━━┳━━━━━━━━━"
echo -e "            ━━━━┫━━━━━━━━━  All at same time!"
echo -e "            ━━━━┻━━━━━━━━━"
echo ""

#==============================================================================
# ADDITIONAL COMMANDS
#==============================================================================

print_header "ADDITIONAL COMMANDS"

echo -e "${GREEN}Generate more traces:${NC}"
echo -e "  Chain:   ${YELLOW}for i in {1..5}; do curl http://localhost:8080/api/chain?data=test-\$i; done${NC}"
echo -e "  Fan-out: ${YELLOW}for i in {1..5}; do curl http://localhost:8080/api/fanout?data=test-\$i; done${NC}"
echo ""

echo -e "${GREEN}Search traces via API:${NC}"
echo -e "  ${YELLOW}curl \"http://localhost:3200/api/search?tags=created.by%3DJ\" | jq .${NC}"
echo -e "  ${YELLOW}curl \"http://localhost:3200/api/search?tags=messaging.pattern%3Dfan-out\" | jq .${NC}"
echo ""

echo -e "${GREEN}View service logs:${NC}"
echo -e "  ${YELLOW}docker logs tel-gateway -f${NC}"
echo -e "  ${YELLOW}docker logs tel-service-e -f${NC}"
echo ""

echo -e "${GREEN}Check RabbitMQ:${NC}"
echo -e "  Management UI: ${YELLOW}http://localhost:15672${NC} (guest/guest)"
echo -e "  See exchanges, queues, and message rates"
echo ""

#==============================================================================
# FOOTER
#==============================================================================

echo -e "${CYAN}=========================================================================${NC}"
echo -e "${GREEN}Demo Complete!${NC} 🎉"
echo -e "${CYAN}=========================================================================${NC}"
echo ""
echo -e "For more information, see:"
echo -e "  • ${YELLOW}README.md${NC} - Project overview"
echo -e "  • ${YELLOW}CUSTOM_METADATA_SAMPLE.md${NC} - Custom metadata guide"
echo -e "  • ${YELLOW}FANOUT_TRACE_EXAMPLE.md${NC} - Fan-out pattern details"
echo ""
