package dev.siegfred.servicee;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration with manual trace context extraction.
 *
 * This configuration enables trace context propagation for:
 * - Message consumption (consumer spans)
 *
 * OTEL semantic conventions for messaging:
 * - messaging.system = "rabbitmq"
 * - messaging.destination.name = queue name
 * - messaging.operation.type = "process"
 */
@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_NAME = "tel.chain.queue";

    @Bean
    public Queue chainQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
