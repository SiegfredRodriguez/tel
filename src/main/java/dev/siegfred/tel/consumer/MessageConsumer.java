package dev.siegfred.tel.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    @RabbitListener(queues = QUEUE_NAME)
    public void receiveMessage(Map<String, Object> message) {
        logger.info("[{}] Received message from RabbitMQ queue: {}", serviceName, QUEUE_NAME);
        logger.info("[{}] Message content: {}", serviceName, message);

        // Simulate processing
        try {
            Thread.sleep(50);
            logger.info("[{}] Successfully processed message from queue", serviceName);
        } catch (InterruptedException e) {
            logger.error("[{}] Error processing message", serviceName, e);
            Thread.currentThread().interrupt();
        }

        logger.info("[{}] Message processing complete - End of chain", serviceName);
    }

    @RabbitListener(queues = "tel.fanout.queue.a")
    public void receiveFanoutMessageA(Map<String, Object> message) {
        logger.info("[{}] Received FANOUT message on Queue A", serviceName);
        processFanoutMessage(message, "Consumer-A");
    }

    @RabbitListener(queues = "tel.fanout.queue.b")
    public void receiveFanoutMessageB(Map<String, Object> message) {
        logger.info("[{}] Received FANOUT message on Queue B", serviceName);
        processFanoutMessage(message, "Consumer-B");
    }

    @RabbitListener(queues = "tel.fanout.queue.c")
    public void receiveFanoutMessageC(Map<String, Object> message) {
        logger.info("[{}] Received FANOUT message on Queue C", serviceName);
        processFanoutMessage(message, "Consumer-C");
    }

    private void processFanoutMessage(Map<String, Object> message, String consumerName) {
        logger.info("[{}] {} processing message: {}", serviceName, consumerName, message);

        // Simulate processing
        try {
            Thread.sleep(100); // Simulate work
            logger.info("[{}] {} successfully processed fanout message", serviceName, consumerName);
        } catch (InterruptedException e) {
            logger.error("[{}] {} error processing message", serviceName, consumerName, e);
            Thread.currentThread().interrupt();
        }

        logger.info("[{}] {} processing complete", serviceName, consumerName);
    }
}
