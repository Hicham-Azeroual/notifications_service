package com.enova.notifications.broker.consumer;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.config.RabbitMQConfig;
import com.enova.notifications.core.mapper.NotificationMapper;
import com.enova.notifications.core.model.NotificationDocument;
import com.enova.notifications.core.service.SsePushService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.ReactiveCassandraOperations;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CentralNotificationConsumer {

    private final ReactiveCassandraOperations cassandraTemplate;
    private final SsePushService ssePushService;
    private final NotificationMapper mapper;

    // TTL 90 jours défini une seule fois, réutilisé à chaque insert
    private static final InsertOptions TTL_90_DAYS =
            InsertOptions.builder().ttl(Duration.ofDays(90)).build();

    public CentralNotificationConsumer(ReactiveCassandraOperations cassandraTemplate,
                                       SsePushService ssePushService,
                                       NotificationMapper mapper) {
        this.cassandraTemplate = cassandraTemplate;
        this.ssePushService    = ssePushService;
        this.mapper            = mapper;
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

        Mono.just(event)
                .map(this::convertToDocument)
                // insert avec TTL 90 jours — remplace l'@Indexed(expireAfterSeconds) de MongoDB
                .flatMap(doc -> cassandraTemplate.insert(doc, TTL_90_DAYS).map(r -> r.getEntity()))
                .map(mapper::toDto)
                .flatMap(ssePushService::push)
                .doOnSuccess(v -> ack(channel, tag))
                .doOnError(e -> {
                    log.error("Erreur traitement notif {}", event.getModuleEmetteur(), e);
                    nack(channel, tag);
                })
                .subscribe();
    }

    private NotificationDocument convertToDocument(GenericNotificationEvent event) {
        return NotificationDocument.builder()
                .id(UUID.randomUUID())
                .createdAt(Instant.now())
                .type(event.getNiveauGravite())
                .typeAlerte(event.getTypeAlerte())
                .titre(event.getTitre())
                .message(event.getMessage())
                .module(event.getModuleEmetteur())
                .etablissementId(event.getEtablissementId())
                .roleCible(event.getRoleCible())
                .destinataires(event.getDestinatairesSpecifiques())
                .metadata(toStringMap(event.getDonneesMetier()))
                .lue(false)
                .acquittee(false)
                .resolue(false)
                .build();
    }

    // Cassandra ne supporte pas Map<String,Object> — on sérialise les valeurs en String
    private Map<String, String> toStringMap(Map<String, Object> source) {
        if (source == null) return null;
        return source.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    }

    private void ack(Channel channel, long tag) {
        try { channel.basicAck(tag, false); } catch (Exception e) { log.warn("ACK failed", e); }
    }

    private void nack(Channel channel, long tag) {
        try { channel.basicNack(tag, false, false); } catch (Exception e) { log.warn("NACK failed", e); }
    }
}
