package dev.siegfred.tel.config;

import io.micrometer.tracing.Tracer;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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

    // Fan-out exchange and queues
    public static final String FANOUT_EXCHANGE = "tel.fanout.exchange";
    public static final String FANOUT_QUEUE_A = "tel.fanout.queue.a";
    public static final String FANOUT_QUEUE_B = "tel.fanout.queue.b";
    public static final String FANOUT_QUEUE_C = "tel.fanout.queue.c";

    @Autowired(required = false)
    private Tracer tracer;

    @Bean
    public Queue chainQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    // Fan-out exchange - broadcasts to all bound queues
    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE, true, false);
    }

    // Three queues for fan-out pattern
    @Bean
    public Queue fanoutQueueA() {
        return new Queue(FANOUT_QUEUE_A, true);
    }

    @Bean
    public Queue fanoutQueueB() {
        return new Queue(FANOUT_QUEUE_B, true);
    }

    @Bean
    public Queue fanoutQueueC() {
        return new Queue(FANOUT_QUEUE_C, true);
    }

    // Bind queues to fanout exchange
    @Bean
    public Binding bindingQueueA(Queue fanoutQueueA, FanoutExchange fanoutExchange) {
        return BindingBuilder.bind(fanoutQueueA).to(fanoutExchange);
    }

    @Bean
    public Binding bindingQueueB(Queue fanoutQueueB, FanoutExchange fanoutExchange) {
        return BindingBuilder.bind(fanoutQueueB).to(fanoutExchange);
    }

    @Bean
    public Binding bindingQueueC(Queue fanoutQueueC, FanoutExchange fanoutExchange) {
        return BindingBuilder.bind(fanoutQueueC).to(fanoutExchange);
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
