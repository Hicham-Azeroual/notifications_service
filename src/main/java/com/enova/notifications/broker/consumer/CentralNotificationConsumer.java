package com.enova.notifications.broker.consumer;

import java.time.LocalDateTime;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.config.RabbitMQConfig;
import com.enova.notifications.core.mapper.NotificationMapper;
import com.enova.notifications.core.model.NotificationDocument;
import com.enova.notifications.core.repository.NotificationRepository;
import com.enova.notifications.core.service.SsePushService;
import com.rabbitmq.client.Channel;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class CentralNotificationConsumer {
    private final NotificationRepository repository ;
    private final SsePushService ssePushService ;
    private final NotificationMapper mapper ;
    public CentralNotificationConsumer ( NotificationRepository r , SsePushService s , NotificationMapper
            m) {
        this . repository = r;
        this . ssePushService = s;
        this . mapper = m;
    }
    // ASTUCE PRO : On coute TOUTES les files des modules en m m e temps !
    @RabbitListener(
            queues = {
                    RabbitMQConfig.QUEUE_PHARMACIE,
                    RabbitMQConfig.QUEUE_URGENCE,
                    RabbitMQConfig.QUEUE_LABO,
                    RabbitMQConfig.QUEUE_CRITIQUE
            },
            ackMode = "MANUAL"
    )
    public void handleAnyModuleEvent (
            GenericNotificationEvent event ,
            Channel channel ,
            @Header( AmqpHeaders. DELIVERY_TAG ) long tag ) {
        log . info (" Alerte r e u e du module [{}] : {}" , event . getModuleEmetteur () , event .
                getTitre () );
        Mono. just ( event )
//                . filterWhen ( this :: estPasDoublon )
                . map ( this :: convertToDocument ) // 1. Transforme Event en Mongo Document
                . flatMap ( repository :: save ) // 2. Sauvegarde dans MongoDB
                . map ( mapper :: toDto ) // 3. Convertit en DTO
                . flatMap ( ssePushService :: push ) // 4. Pousse via SSE
                . doOnSuccess (v -> ack ( channel , tag )) // 5. ACK si tout OK
                . doOnError ( e -> {
                    log . error (" Erreur traitement notif {}" , event . getModuleEmetteur () , e) ;
                    nack ( channel , tag ); // 6. NACK Dead Letter Queue
                })
                . subscribe () ;
    }
    // SOLUTION — Aligner les champs ou séparer typeAlerte et niveauGravite

    // Option A : Stocker typeAlerte dans le document
    private NotificationDocument convertToDocument(GenericNotificationEvent event) {
        return NotificationDocument.builder()
                .type(event.getNiveauGravite())      // CRITIQUE, WARNING, INFO
                .typeAlerte(event.getTypeAlerte())   // RUPTURE_STOCK, CODE_ROUGE ← ajouter ce champ
                .titre(event.getTitre())
                .message(event.getMessage())
                .module(event.getModuleEmetteur())
                .etablissementId(event.getEtablissementId())
                .roleCible(event.getRoleCible())
                .destinataires(event.getDestinatairesSpecifiques())
                .metadata(event.getDonneesMetier())
                .lue(false)
                .acquittee(false)
                .resolue(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // Et la déduplication sur typeAlerte (le vrai identifiant métier)
    private Mono<Boolean> estPasDoublon(GenericNotificationEvent event) {
        return repository
                .existsByModuleAndTypeAlerteAndEtablissementIdAndAcquitteeFalse(
                        event.getModuleEmetteur(),
                        event.getTypeAlerte(),       // ← typeAlerte, pas niveauGravite
                        event.getEtablissementId()
                )
                .map(exists -> !exists);
    }
    private void ack ( Channel channel , long tag ) {
        try { channel . basicAck ( tag , false ); } catch ( Exception e) {}
    }
    private void nack ( Channel channel , long tag ) {
        try { channel . basicNack ( tag , false , false ); } catch ( Exception e) {}
    }
}
