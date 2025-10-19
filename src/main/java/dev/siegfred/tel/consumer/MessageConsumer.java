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

    @RabbitListener(queues = "tel.fanout.queue.a")
    public void receiveFanoutMessageA(Map<String, Object> message, @Headers Map<String, Object> headers) {
        logger.info("[{}] Received FANOUT message on Queue A", serviceName);
        processFanoutMessage(message, "tel.fanout.queue.a", "Consumer-A");
    }

    @RabbitListener(queues = "tel.fanout.queue.b")
    public void receiveFanoutMessageB(Map<String, Object> message, @Headers Map<String, Object> headers) {
        logger.info("[{}] Received FANOUT message on Queue B", serviceName);
        processFanoutMessage(message, "tel.fanout.queue.b", "Consumer-B");
    }

    @RabbitListener(queues = "tel.fanout.queue.c")
    public void receiveFanoutMessageC(Map<String, Object> message, @Headers Map<String, Object> headers) {
        logger.info("[{}] Received FANOUT message on Queue C", serviceName);
        processFanoutMessage(message, "tel.fanout.queue.c", "Consumer-C");
    }

    private void processFanoutMessage(Map<String, Object> message, String queueName, String consumerName) {
        logger.info("[{}] {} processing message: {}", serviceName, consumerName, message);

        // Add custom tags to the automatically created span
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            logger.info("[{}] {} processing in span: traceId={}, spanId={}",
                    serviceName, consumerName, currentSpan.context().traceId(), currentSpan.context().spanId());

            // Add OTEL semantic conventions for messaging
            currentSpan.tag("service.name", serviceName);
            currentSpan.tag("consumer.name", consumerName);
            currentSpan.tag("messaging.pattern", "fan-out");
            currentSpan.tag("business.operation", "fanout-message-processing");
            currentSpan.tag("messaging.system", "rabbitmq");
            currentSpan.tag("messaging.destination.name", queueName);
            currentSpan.tag("messaging.operation.type", "receive");
            currentSpan.tag("messaging.consumer.type", "parallel");

            // Extract message data if available
            if (message.containsKey("data")) {
                currentSpan.tag("message.data", String.valueOf(message.get("data")));
            }
            if (message.containsKey("source_service")) {
                currentSpan.tag("message.source", String.valueOf(message.get("source_service")));
            }
        } else {
            logger.warn("[{}] {} No active span found", serviceName, consumerName);
        }

        // Simulate processing
        try {
            Thread.sleep(100); // Simulate work
            logger.info("[{}] {} successfully processed fanout message", serviceName, consumerName);
        } catch (InterruptedException e) {
            logger.error("[{}] {} error processing message", serviceName, consumerName, e);
            if (currentSpan != null) {
                currentSpan.error(e);
            }
            Thread.currentThread().interrupt();
        }

        logger.info("[{}] {} processing complete", serviceName, consumerName);
    }
}
