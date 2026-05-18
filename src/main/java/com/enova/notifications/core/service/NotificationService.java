package com.enova.notifications.core.service;

import com.enova.notifications.core.dto.NotificationDTO;
import com.enova.notifications.core.mapper.NotificationMapper;
import com.enova.notifications.core.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationMapper mapper;
    private final SsePushService ssePushService;

    public Flux<NotificationDTO> getUnreadNotifications(String etablissementId, String roleCible) {
        log.debug("Notifications non lues | etablId={} | role={}", etablissementId, roleCible);
        return repository.findByEtablissementIdAndRoleCibleAndLueFalse(etablissementId, roleCible)
                .sort(Comparator.comparing(doc -> doc.getCreatedAt() != null ? doc.getCreatedAt() : Instant.EPOCH,
                        Comparator.reverseOrder()))
                .map(mapper::toDto);
    }

    public Flux<NotificationDTO> getAllNotifications() {
        log.debug("Récupération de toutes les notifications");
        return repository.findAll().sort(Comparator.comparing(doc -> doc.getCreatedAt() != null ? doc.getCreatedAt() : Instant.EPOCH,
                Comparator.reverseOrder()))
                .map(mapper::toDto);
    }

    public Mono<Long> countUnreadNotifications() {
        return repository.countByLueFalse();
    }

    public Mono<NotificationDTO> acquitterNotification(String notificationId, String userId) {
        log.info("Acquittement | notificationId={} | userId={}", notificationId, userId);
        UUID id = UUID.fromString(notificationId);

        return repository.findById(id)
                .filter(notif -> !notif.getAcquittee())
                .switchIfEmpty(Mono.error(new RuntimeException("Notification introuvable ou déjà traitée")))
                .flatMap(notif -> {
                    notif.setAcquittee(true);
                    notif.setLue(true);
                    notif.setAcquittePar(userId);
                    notif.setAcquitteeAt(Instant.now());
                    notif.setLueAt(Instant.now());
                    return repository.save(notif);
                })
                .map(mapper::toDto)
                .flatMap(dto -> ssePushService.push(dto).thenReturn(dto));
    }

    public Mono<NotificationDTO> marquerCommeLue(String notificationId) {
        UUID id = UUID.fromString(notificationId);
        return repository.findById(id)
                .filter(notif -> !notif.getLue())
                .flatMap(notif -> {
                    notif.setLue(true);
                    notif.setLueAt(Instant.now());
                    return repository.save(notif);
                })
                .map(mapper::toDto);
    }
}
