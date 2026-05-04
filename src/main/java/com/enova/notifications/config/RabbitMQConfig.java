package com.enova.notifications.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_EVENTS = "sih.events";
    public static final String EXCHANGE_DLX = "dlx.sih";

    public static final String QUEUE_PHARMACIE = "queue.notif.pharmacie";
    public static final String QUEUE_URGENCE = "queue.notif.urgence";
    public static final String QUEUE_LABO = "queue.notif.labo";
    public static final String QUEUE_CRITIQUE = "queue.notif.critique";
    public static final String QUEUE_DEAD_LETTER = "queue.notif.dead-letter";

    @Bean
    public TopicExchange sihExchange() {
        return new TopicExchange(EXCHANGE_EVENTS, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(EXCHANGE_DLX, true, false);
    }

    @Bean
    public Queue pharmacieQueue() {
        return QueueBuilder.durable(QUEUE_PHARMACIE)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "erreur.pharmacie")
                .build();
    }

    @Bean
    public Queue urgenceQueue() {
        return QueueBuilder.durable(QUEUE_URGENCE)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "erreur.urgence")
                .build();
    }

    @Bean
    public Queue laboQueue() {
        return QueueBuilder.durable(QUEUE_LABO)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "erreur.labo")
                .build();
    }

    @Bean
    public Queue critiqueQueue() {
        return QueueBuilder.durable(QUEUE_CRITIQUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "erreur.critique")
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DEAD_LETTER).build();
    }

    @Bean
    public Binding pharmacieBinding(Queue pharmacieQueue, TopicExchange sihExchange) {
        return BindingBuilder.bind(pharmacieQueue).to(sihExchange).with("pharmacie.#");
    }

    @Bean
    public Binding urgenceBinding(Queue urgenceQueue, TopicExchange sihExchange) {
        return BindingBuilder.bind(urgenceQueue).to(sihExchange).with("urgence.#");
    }

    @Bean
    public Binding laboBinding(Queue laboQueue, TopicExchange sihExchange) {
        return BindingBuilder.bind(laboQueue).to(sihExchange).with("labo.#");
    }

    @Bean
    public Binding critiqueBinding(Queue critiqueQueue, TopicExchange sihExchange) {
        return BindingBuilder.bind(critiqueQueue).to(sihExchange).with("#.critique");
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("erreur.#");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }




}