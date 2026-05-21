package com.enova.notifications.broker.consumer;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.broker.processor.NotificationProcessorFactory;
import com.enova.notifications.config.RabbitMQConfig;
import com.enova.notifications.core.mapper.NotificationMapper;
import com.enova.notifications.core.service.SsePushService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.ReactiveCassandraOperations;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Orchestrateur pur — reçoit depuis RabbitMQ, délègue au bon processor, gère ACK/NACK.
 * Aucune logique métier ici. Ajouter un module = créer un NotificationProcessor, pas toucher ici.
 */
@Component
@Slf4j
public class CentralNotificationConsumer {

    private final ReactiveCassandraOperations cassandraTemplate;
    private final SsePushService ssePushService;
    private final NotificationMapper mapper;
    private final NotificationProcessorFactory processorFactory;

    private static final InsertOptions TTL_90_DAYS =
            InsertOptions.builder().ttl(Duration.ofDays(90)).build();

    public CentralNotificationConsumer(ReactiveCassandraOperations cassandraTemplate,
                                       SsePushService ssePushService,
                                       NotificationMapper mapper,
                                       NotificationProcessorFactory processorFactory) {
        this.cassandraTemplate  = cassandraTemplate;
        this.ssePushService     = ssePushService;
        this.mapper             = mapper;
        this.processorFactory   = processorFactory;
    }

    @RabbitListener(
            queues = {
                    RabbitMQConfig.QUEUE_PHARMACIE,
                    RabbitMQConfig.QUEUE_URGENCE,
                    RabbitMQConfig.QUEUE_LABO,
                    RabbitMQConfig.QUEUE_CRITIQUE
            },
            ackMode = "MANUAL"
    )
    public void handleAnyModuleEvent(
            GenericNotificationEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag) {

        log.info("Alerte reçue du module [{}] : {}", event.getModuleEmetteur(), event.getTitre());

        processorFactory.getProcessor(event.getModuleEmetteur())
                .process(event)
                .flatMap(doc -> cassandraTemplate.insert(doc, TTL_90_DAYS).map(r -> r.getEntity()))
                .map(mapper::toDto)
                .doOnSuccess(dto -> ack(channel, tag)) // ACK = Mono terminé avec succès
                .doOnSuccess(ssePushService::push)     // push SSE best-effort
                .doOnError(e -> {
                    log.error("Erreur traitement notif module={}", event.getModuleEmetteur(), e);
                    nack(channel, tag);
                })
                .subscribe();
    }

    private void ack(Channel channel, long tag) {
        try { channel.basicAck(tag, false); } catch (Exception e) { log.warn("ACK failed", e); }
    }

    private void nack(Channel channel, long tag) {
        try { channel.basicNack(tag, false, false); } catch (Exception e) { log.warn("NACK failed", e); }
    }
}
