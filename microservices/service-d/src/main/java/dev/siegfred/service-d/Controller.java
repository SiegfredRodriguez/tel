package dev.siegfred.serviced;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Service D - Publishes messages to RabbitMQ queue.
 *
 * This service receives HTTP requests and forwards the data to service-e
 * via RabbitMQ, demonstrating trace context propagation across messaging systems.
 *
 * OTEL semantic conventions for messaging:
 * - messaging.system = "rabbitmq"
 * - messaging.destination.name = "tel.chain.queue"
 * - messaging.operation.type = "send"
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class Controller {

    private static final String QUEUE_NAME = "tel.chain.queue";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private Tracer tracer;

    @GetMapping("/process")
    public Map<String, Object> process(@RequestParam(defaultValue = "data") String data) {
        log.info("[service-d] Received request with data: {}", data);

        Map<String, Object> response = new HashMap<>();
        response.put("service", "service-d");
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());

        // Publish message to RabbitMQ - trace context automatically propagated
        try {
            log.info("[service-d] Publishing message to RabbitMQ queue: {}", QUEUE_NAME);

            // Add custom span attributes for the messaging operation
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                currentSpan.tag("messaging.system", "rabbitmq");
                currentSpan.tag("messaging.destination.name", QUEUE_NAME);
                currentSpan.tag("messaging.operation.type", "send");
                currentSpan.tag("service.description", "Service D - RabbitMQ producer");
                currentSpan.tag("component.type", "RabbitMQ Producer");
            }

            // Prepare message payload
            Map<String, Object> message = new HashMap<>();
            message.put("service", "service-d");
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            // Send to RabbitMQ - OTEL instrumentation auto-injects trace headers
            rabbitTemplate.convertAndSend(QUEUE_NAME, message);

            log.info("[service-d] Successfully published message to RabbitMQ");
            response.put("message", "Message sent to RabbitMQ queue: " + QUEUE_NAME);
            response.put("next", "service-e (via RabbitMQ)");

        } catch (Exception e) {
            log.error("[service-d] Error publishing to RabbitMQ", e);
            response.put("error", "Failed to publish to RabbitMQ: " + e.getMessage());
        }

        log.info("[service-d] Returning response");
        return response;
    }
}
