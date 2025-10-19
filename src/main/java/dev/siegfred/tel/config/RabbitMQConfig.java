package dev.siegfred.tel.config;

import io.micrometer.tracing.Tracer;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration with automatic trace propagation via Micrometer Observation.
 * Only enabled when RabbitMQ is configured (service-d and service-e).
 */
@Configuration
@ConditionalOnProperty(name = "spring.rabbitmq.host")
public class RabbitMQConfig {

    public static final String QUEUE_NAME = "tel.chain.queue";

    @Autowired(required = false)
    private Tracer tracer;

    @Bean
    public Queue chainQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate with observation enabled for automatic trace propagation.
     * Micrometer Observation handles trace context injection automatically.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());

        // Enable observation for automatic trace propagation
        template.setObservationEnabled(true);

        return template;
    }

    /**
     * RabbitListener container factory with observation enabled.
     * This enables automatic trace context extraction and span creation for consumers.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        // Enable observation for automatic trace propagation
        factory.setObservationEnabled(true);

        // Set message converter
        factory.setMessageConverter(messageConverter());

        return factory;
    }
}
