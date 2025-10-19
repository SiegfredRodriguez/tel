package dev.siegfred.servicee;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ message consumer with OpenTelemetry instrumentation.
 *
 * The @RabbitListener annotation combined with OTEL instrumentation
 * automatically creates consumer spans and propagates trace context.
 *
 * Semantic conventions applied:
 * - messaging.system = "rabbitmq"
 * - messaging.destination.name = "tel.chain.queue"
 * - messaging.operation.type = "process"
 * - messaging.rabbitmq.destination.routing_key = routing key
 */
@Slf4j
@Component
public class MessageConsumer {

    @Autowired
    private Tracer tracer;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void receiveMessage(Map<String, Object> message, @org.springframework.messaging.handler.annotation.Headers Map<String, Object> headers) {
        log.info("[service-e] Received message from RabbitMQ queue: {}", RabbitMQConfig.QUEUE_NAME);
        log.info("[service-e] Message content: {}", message);

        // Log trace context from message headers
        if (headers.containsKey("traceId")) {
            log.info("[service-e] Trace context from message: traceId={}, spanId={}",
                    headers.get("traceId"), headers.get("spanId"));
        }

        // Add custom span attributes for enriched trace metadata
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            // Business attributes
            currentSpan.tag("service.name", "service-e");
            currentSpan.tag("service.description", "RabbitMQ consumer - End of microservices chain");
            currentSpan.tag("business.operation", "message-processing");
            currentSpan.tag("messaging.system", "rabbitmq");
            currentSpan.tag("messaging.destination.name", RabbitMQConfig.QUEUE_NAME);
            currentSpan.tag("messaging.operation.type", "process");

            // Technical metadata
            currentSpan.tag("programming.language", "Java 17");
            currentSpan.tag("framework", "Spring Boot 3.5.6");
            currentSpan.tag("component.type", "RabbitMQ Consumer");

            // Extract message data if available
            if (message.containsKey("data")) {
                currentSpan.tag("message.data", String.valueOf(message.get("data")));
            }
        }

        // Simulate processing
        try {
            Thread.sleep(50);
            log.info("[service-e] Successfully processed message from queue");
        } catch (InterruptedException e) {
            log.error("[service-e] Error processing message", e);
            Thread.currentThread().interrupt();
        }

        log.info("[service-e] Message processing complete - End of chain");
    }
}
