package dev.siegfred.tel.consumer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ message consumer for service-e.
 * Only enabled when service name is service-e.
 * Automatic trace propagation via Spring AMQP Micrometer Observation.
 */
@Component
@ConditionalOnProperty(name = "service.name", havingValue = "service-e")
public class MessageConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MessageConsumer.class);
    private static final String QUEUE_NAME = "tel.chain.queue";

    @Value("${service.name:unknown}")
    private String serviceName;

    @Autowired
    private Tracer tracer;

    @RabbitListener(queues = QUEUE_NAME)
    public void receiveMessage(Map<String, Object> message, @Headers Map<String, Object> headers) {
        logger.info("[{}] Received message from RabbitMQ queue: {}", serviceName, QUEUE_NAME);
        logger.info("[{}] Message content: {}", serviceName, message);

        // Observation automatically extracts trace context and creates span
        // Add custom tags to the automatically created span
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            logger.info("[{}] Processing in span: traceId={}, spanId={}",
                    serviceName, currentSpan.context().traceId(), currentSpan.context().spanId());

            // Add OTEL semantic conventions for messaging
            currentSpan.tag("service.name", serviceName);
            currentSpan.tag("service.description", "RabbitMQ consumer - End of microservices chain");
            currentSpan.tag("business.operation", "message-processing");
            currentSpan.tag("messaging.system", "rabbitmq");
            currentSpan.tag("messaging.destination.name", QUEUE_NAME);
            currentSpan.tag("messaging.operation.type", "process");

            // Technical metadata
            currentSpan.tag("programming.language", "Java 17");
            currentSpan.tag("framework", "Spring Boot 3.5.6");
            currentSpan.tag("component.type", "RabbitMQ Consumer");

            // Extract message data if available
            if (message.containsKey("data")) {
                currentSpan.tag("message.data", String.valueOf(message.get("data")));
            }
        } else {
            logger.warn("[{}] No active span found - trace propagation may have failed", serviceName);
        }

        // Simulate processing
        try {
            Thread.sleep(50);
            logger.info("[{}] Successfully processed message from queue", serviceName);
        } catch (InterruptedException e) {
            logger.error("[{}] Error processing message", serviceName, e);
            if (currentSpan != null) {
                currentSpan.error(e);
            }
            Thread.currentThread().interrupt();
        }

        logger.info("[{}] Message processing complete - End of chain", serviceName);
    }
}
