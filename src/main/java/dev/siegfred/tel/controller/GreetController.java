package dev.siegfred.tel.controller;

import dev.siegfred.tel.client.ChainServiceClient;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api")
public class GreetController {

    private static final Logger logger = LoggerFactory.getLogger(GreetController.class);
    private final Random random = new Random();

    @Value("${service.name:tel}")
    private String serviceName;

    @Value("${service.next.url:}")
    private String nextServiceUrl;

    @Autowired(required = false)
    private ChainServiceClient chainServiceClient;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private Tracer tracer;

    @Autowired
    private RestClient restClient;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    private static final String RABBITMQ_QUEUE = "tel.chain.queue";

    @GetMapping("/greet")
    public Map<String, String> greet(@RequestParam(required = false, defaultValue = "World") String name) {
        logger.info("Received greet request for name: {}", name);

        // Simulate some processing time
        int processingTime = random.nextInt(100);
        logger.debug("Processing will take {} ms", processingTime);

        try {
            Thread.sleep(processingTime);
            logger.info("Successfully processed greeting for {}", name);
            return Map.of(
                "message", "hello",
                "name", name,
                "timestamp", String.valueOf(System.currentTimeMillis())
            );
        } catch (InterruptedException e) {
            logger.error("Error processing greeting request", e);
            Thread.currentThread().interrupt();
            return Map.of("error", "Processing interrupted");
        }
    }

    @GetMapping("/error")
    public Map<String, String> simulateError() {
        logger.warn("Error endpoint called - simulating error condition");

        try {
            logger.error("Simulating application error for demonstration");
            throw new RuntimeException("This is a simulated error for trace-log correlation demo");
        } catch (Exception e) {
            logger.error("Caught exception in error endpoint", e);
            throw e;
        }
    }

    @GetMapping("/chain")
    public Map<String, Object> chain(@RequestParam(required = false, defaultValue = "data") String data) {
        logger.info("[{}] Received chain request with data: {}", serviceName, data);

        // Add custom span attributes for enriched trace metadata
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            // Add custom business attributes
            currentSpan.tag("request.data", data);
            currentSpan.tag("service.description", "Microservice chain handler - " + serviceName);
            currentSpan.tag("business.operation", "chain-processing");
            currentSpan.tag("request.size", String.valueOf(data.length()));

            // Add technical metadata
            currentSpan.tag("programming.language", "Java 17");
            currentSpan.tag("framework", "Spring Boot 3.5.6");
            currentSpan.tag("component.type", "REST Controller");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("service", serviceName);
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());

        // Service-d publishes to RabbitMQ, others use Feign for HTTP calls
        if ("service-d".equals(serviceName)) {
            // Service-d: Publish to RabbitMQ instead of HTTP call
            if (rabbitTemplate != null) {
                try {
                    logger.info("[{}] Publishing message to RabbitMQ queue: {}", serviceName, RABBITMQ_QUEUE);

                    // Add custom span tags to current span (observation will create the span automatically)
                    if (currentSpan != null) {
                        currentSpan.tag("messaging.system", "rabbitmq");
                        currentSpan.tag("messaging.destination.name", RABBITMQ_QUEUE);
                        currentSpan.tag("messaging.operation.type", "send");
                        currentSpan.tag("service.name", serviceName);
                    }

                    // Prepare message payload
                    Map<String, Object> message = new HashMap<>();
                    message.put("service", serviceName);
                    message.put("data", data);
                    message.put("timestamp", System.currentTimeMillis());

                    // Publish to RabbitMQ - observation automatically creates span and propagates trace context
                    rabbitTemplate.convertAndSend(RABBITMQ_QUEUE, message);

                    logger.info("[{}] Successfully published message to RabbitMQ", serviceName);
                    response.put("message", "Message sent to RabbitMQ queue: " + RABBITMQ_QUEUE);
                    response.put("next", "service-e (via RabbitMQ)");

                } catch (Exception e) {
                    logger.error("[{}] Error publishing to RabbitMQ", serviceName, e);
                    response.put("error", "Failed to publish to RabbitMQ: " + e.getMessage());
                }
            } else {
                logger.warn("[{}] RabbitTemplate not available", serviceName);
                response.put("message", "End of chain - RabbitMQ not configured");
            }
        } else if (nextServiceUrl != null && !nextServiceUrl.isEmpty() && chainServiceClient != null) {
            // All other services: Use Feign for HTTP calls
            try {
                logger.info("[{}] Calling next service via Feign: {}", serviceName, nextServiceUrl);

                Map<String, Object> nextResponse = chainServiceClient.chain(data);

                response.put("next", nextResponse);
                logger.info("[{}] Successfully received response from next service", serviceName);
            } catch (Exception e) {
                logger.error("[{}] Error calling next service: {}", serviceName, nextServiceUrl, e);
                response.put("error", "Failed to call next service: " + e.getMessage());
            }
        } else {
            logger.info("[{}] End of chain reached", serviceName);
            response.put("message", "End of chain");
        }

        logger.info("[{}] Returning response", serviceName);
        return response;
    }

    @GetMapping("/fanout")
    public Map<String, Object> fanout(@RequestParam(required = false, defaultValue = "fanout-test") String data) {
        logger.info("[{}] Received RabbitMQ fan-out request with data: {}", serviceName, data);

        // Add custom span attributes
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("request.data", data);
            currentSpan.tag("request.type", "rabbitmq-fanout");
            currentSpan.tag("messaging.pattern", "fan-out");
            currentSpan.tag("messaging.exchange", "tel.fanout.exchange");
            currentSpan.tag("fan-out.queues", "tel.fanout.queue.a,tel.fanout.queue.b,tel.fanout.queue.c");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("service", serviceName);
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());
        response.put("pattern", "rabbitmq-fanout");

        if (rabbitTemplate != null) {
            try {
                logger.info("[{}] Publishing message to fan-out exchange: tel.fanout.exchange", serviceName);

                // Prepare message payload
                Map<String, Object> message = new HashMap<>();
                message.put("source_service", serviceName);
                message.put("data", data);
                message.put("timestamp", System.currentTimeMillis());
                message.put("message_type", "fanout");

                // Publish to fanout exchange (will be delivered to all bound queues)
                rabbitTemplate.convertAndSend("tel.fanout.exchange", "", message);

                logger.info("[{}] Successfully published message to fan-out exchange", serviceName);
                response.put("status", "Message sent to fanout exchange");
                response.put("exchange", "tel.fanout.exchange");
                response.put("target_queues", List.of("tel.fanout.queue.a", "tel.fanout.queue.b", "tel.fanout.queue.c"));
                response.put("message", "Message will be delivered to ALL 3 consumers in parallel");

            } catch (Exception e) {
                logger.error("[{}] Error publishing to fan-out exchange", serviceName, e);
                response.put("error", "Failed to publish to fan-out exchange: " + e.getMessage());
            }
        } else {
            logger.warn("[{}] RabbitTemplate not available", serviceName);
            response.put("error", "RabbitMQ not configured for this service");
        }

        return response;
    }
}
