package com.enova.notifications.core.service;

import com.enova.notifications.core.dto.NotificationDTO;
import com.enova.notifications.core.mapper.NotificationMapper;
import com.enova.notifications.core.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
 @Service
    @Slf4j
    @RequiredArgsConstructor // Lombok génère automatiquement le constructeur pour l'injection de dépendances
    public class NotificationService {

        private final NotificationRepository repository;
        private final NotificationMapper mapper;
        private final SsePushService ssePushService;

        /**
         * Récupère l'historique des notifications non lues pour un profil donné.
         */
        public Flux<NotificationDTO> getUnreadNotifications(String etablissementId, String roleCible) {
            log.debug("Récupération des notifications non lues | etablId={} | role={}", etablissementId, roleCible);
        System.out.println("Récupération des notifications non lues | etablId={} | role={}");
            return repository
                    .findByEtablissementIdAndRoleCibleAndLueFalseOrderByCreatedAtDesc(etablissementId, roleCible)
                    .map(mapper::toDto);
        }

        /**
         * Récupère toutes les notifications sans filtre.
         */
        public Flux<NotificationDTO> getAllNotifications() {
            log.debug("Récupération de toutes les notifications");
            return repository.findAll()
                    .map(mapper::toDto);
        }

        /**
         * Compte le nombre de notifications non lues (pour le badge rouge dans le frontend React).
         */
        public Mono<Long> countUnreadNotifications() {
            return repository.countByLueFalse();
        }

        /**
         * Acquitte une notification (Action de l'utilisateur qui dit "J'ai pris en charge ce problème").
         * L'acquittement marque la notification comme lue et enregistre l'utilisateur qui l'a fait.
         */
        public Mono<NotificationDTO> acquitterNotification(String notificationId, String userId) {
            log.info("Tentative d'acquittement | notificationId={} | userId={}", notificationId, userId);

            return repository.findById(notificationId)
                    // 1. On vérifie que la notification n'est pas déjà acquittée par un autre collègue
                    .filter(notif -> !notif.getAcquittee())
                    .switchIfEmpty(Mono.error(new RuntimeException("Notification introuvable ou déjà traitée")))

                    // 2. On met à jour les champs métier
                    .flatMap(notif -> {
                        notif.setAcquittee(true);
                        notif.setLue(true);
                        notif.setAcquittePar(userId);
                        notif.setAcquitteeAt(LocalDateTime.now());
                        notif.setLueAt(LocalDateTime.now());
                        return repository.save(notif);
                    })

                    // 3. On convertit en DTO
                    .map(mapper::toDto)

                    // 4. BONUS : On repousse la notification mise à jour via SSE !
                    // Pourquoi ? Si le Pharmacien A acquitte l'alerte, on pousse le nouvel état via SSE
                    // pour que l'alerte disparaisse instantanément de l'écran du Pharmacien B !
                    .flatMap(dto -> ssePushService.push(dto).thenReturn(dto));
        }

        /**
         * Marque simplement une notification comme lue (sans l'acquitter).
         * Utile si l'utilisateur a juste ouvert le panel de notifications.
         */
        public Mono<NotificationDTO> marquerCommeLue(String notificationId) {
            return repository.findById(notificationId)
                    .filter(notif -> !notif.getLue())
                    .flatMap(notif -> {
                        notif.setLue(true);
                        notif.setLueAt(LocalDateTime.now());
                        return repository.save(notif);
                    })
                    .map(mapper::toDto);
        }
    }

