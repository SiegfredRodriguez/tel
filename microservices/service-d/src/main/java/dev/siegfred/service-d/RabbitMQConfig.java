package dev.siegfred.serviced;

import io.micrometer.tracing.Tracer;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for service-d (producer).
 *
 * Manually injects trace context into RabbitMQ message headers
 * following OTEL semantic conventions for messaging systems.
 */
@Configuration
public class RabbitMQConfig {

    @Autowired
    private Tracer tracer;

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate with manual trace context injection.
     * Adds traceId and spanId to message headers for distributed tracing.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());

        // Add message post-processor to inject trace context
        template.setBeforePublishPostProcessors(traceContextInjector());

        return template;
    }

    /**
     * Message post-processor that injects trace context into message headers.
     * Follows W3C Trace Context specification.
     */
    private MessagePostProcessor traceContextInjector() {
        return message -> {
            if (tracer != null && tracer.currentSpan() != null) {
                var context = tracer.currentSpan().context();

                // Inject traceId and spanId into message headers
                message.getMessageProperties().setHeader("traceId", context.traceId());
                message.getMessageProperties().setHeader("spanId", context.spanId());

                // Also add as traceparent header (W3C format)
                String traceparent = String.format("00-%s-%s-01",
                        context.traceId(),
                        context.spanId());
                message.getMessageProperties().setHeader("traceparent", traceparent);
            }
            return message;
        };
    }
}
