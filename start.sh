#!/bin/bash

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}=========================================================================${NC}"
echo -e "${CYAN}                   OpenTelemetry Demo - Startup Script${NC}"
echo -e "${CYAN}=========================================================================${NC}"
echo ""

# Function to print section headers
print_header() {
    echo -e "\n${BLUE}>>> $1${NC}\n"
}

# Function to print success messages
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

# Function to print error messages
print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Function to print info messages
print_info() {
    echo -e "${YELLOW}➜ $1${NC}"
}

# Check prerequisites
print_header "Checking Prerequisites"

# Check Java
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
    print_success "Java found: $JAVA_VERSION"
else
    print_error "Java not found! Please install Java 17 or higher"
    exit 1
fi

# Check Docker
if command -v docker &> /dev/null; then
    DOCKER_VERSION=$(docker --version | cut -d' ' -f3)
    print_success "Docker found: $DOCKER_VERSION"
else
    print_error "Docker not found! Please install Docker"
    exit 1
fi

# Check Docker Compose
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE_VERSION=$(docker-compose --version | cut -d' ' -f4)
    print_success "Docker Compose found: $DOCKER_COMPOSE_VERSION"
else
    print_error "Docker Compose not found! Please install Docker Compose"
    exit 1
fi

# Clean up old containers
print_header "Cleaning Up Old Containers"
print_info "Stopping and removing old containers..."
docker-compose -f docker-compose-microservices.yml down -v 2>/dev/null
print_success "Cleanup complete"

# Build the application
print_header "Building Application"
print_info "Running Gradle build (this may take a minute)..."
./gradlew clean build -x test

if [ $? -eq 0 ]; then
    print_success "Application built successfully"
else
    print_error "Build failed! Please check the error messages above"
    exit 1
fi

# Start infrastructure services first
print_header "Starting Infrastructure Services"
print_info "Starting Tempo, Prometheus, Loki, Grafana, RabbitMQ, OTEL Collector..."
docker-compose -f docker-compose-microservices.yml up -d tempo prometheus loki grafana rabbitmq otel-collector

print_info "Waiting for infrastructure to be ready (15 seconds)..."
sleep 15

# Start microservices
print_header "Starting Microservices"
print_info "Building and starting all microservices..."
docker-compose -f docker-compose-microservices.yml up -d --build gateway service-a service-b service-c service-d service-e

print_info "Waiting for services to be ready (20 seconds)..."
for i in {20..1}; do
    echo -ne "${YELLOW}Waiting: $i seconds remaining...\r${NC}"
    sleep 1
done
echo ""

# Check service health
print_header "Checking Service Health"

SERVICES=("gateway:8080" "service-a:8081" "service-b:8082" "service-c:8083" "service-d:8084" "service-e:8085")
ALL_HEALTHY=true

for SERVICE in "${SERVICES[@]}"; do
    NAME=$(echo $SERVICE | cut -d':' -f1)
    PORT=$(echo $SERVICE | cut -d':' -f2)

    if curl -s -f "http://localhost:$PORT/actuator/health" > /dev/null 2>&1; then
        print_success "$NAME is healthy"
    else
        print_error "$NAME is not responding"
        ALL_HEALTHY=false
    fi
done

# Check infrastructure
print_info "Checking infrastructure services..."
if curl -s -f "http://localhost:3000/api/health" > /dev/null 2>&1; then
    print_success "Grafana is ready"
else
    print_error "Grafana is not responding"
    ALL_HEALTHY=false
fi

if curl -s -f "http://localhost:3200/ready" > /dev/null 2>&1; then
    print_success "Tempo is ready"
else
    print_error "Tempo is not responding"
    ALL_HEALTHY=false
fi

# Display summary
echo ""
echo -e "${CYAN}=========================================================================${NC}"
echo -e "${CYAN}                          STARTUP COMPLETE${NC}"
echo -e "${CYAN}=========================================================================${NC}"
echo ""

if [ "$ALL_HEALTHY" = true ]; then
    print_success "All services are running and healthy!"
else
    print_error "Some services failed to start properly. Check logs with:"
    echo -e "  ${YELLOW}docker-compose -f docker-compose-microservices.yml logs [service-name]${NC}"
fi

echo ""
echo -e "${GREEN}Service URLs:${NC}"
echo -e "  • Gateway (API):        http://localhost:8080"
echo -e "  • Grafana (UI):         http://localhost:3000"
echo -e "  • Prometheus (Metrics): http://localhost:9090"
echo -e "  • RabbitMQ (UI):        http://localhost:15672 (guest/guest)"
echo ""

echo -e "${GREEN}Quick Test Commands:${NC}"
echo -e "  • Chain request:  ${YELLOW}curl http://localhost:8080/api/chain?data=test${NC}"
echo -e "  • Fan-out request:${YELLOW}curl http://localhost:8080/api/fanout?data=test${NC}"
echo ""

echo -e "${GREEN}Next Steps:${NC}"
echo -e "  1. Run the demo script:  ${YELLOW}./demo.sh${NC}"
echo -e "  2. Open Grafana:         ${YELLOW}http://localhost:3000${NC}"
echo -e "  3. Explore traces in Tempo datasource"
echo ""

echo -e "${GREEN}To stop all services:${NC}"
echo -e "  ${YELLOW}docker-compose -f docker-compose-microservices.yml down${NC}"
echo ""

echo -e "${CYAN}=========================================================================${NC}"
