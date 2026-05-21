package com.enova.notifications.core.controller;

import com.enova.notifications.core.dto.NotificationDTO;
import com.enova.notifications.core.service.NotificationService;
import com.enova.notifications.core.service.SsePushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final SsePushService ssePushService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<NotificationDTO>> stream(
            @RequestParam String userId,
            @RequestParam String role,
            @RequestParam String etablissementId) {

        // S'inscrire EN PREMIER dans l'inverted index — le sink bufferise
        // toutes les notifications qui arrivent pendant qu'on lit Cassandra
        Flux<ServerSentEvent<NotificationDTO>> realtime =
                ssePushService.subscribe(userId, role, etablissementId);

        // Récupérer les notifications non lues (user était offline)
        Flux<ServerSentEvent<NotificationDTO>> historical =
                notificationService.getUnreadNotifications(etablissementId, role)
                        .map(dto -> ServerSentEvent.<NotificationDTO>builder()
                                .event("notification")
                                .data(dto)
                                .build());

        // Historique d'abord, puis temps réel sans interruption
        return Flux.concat(historical, realtime);
    }

    // =========================================================
    // API REST CLASSIQUES
    // =========================================================

    @GetMapping
    public Flux<NotificationDTO> getNotifications() {
        return notificationService.getAllNotifications();
    }

    @GetMapping("/count")
    public Mono<Long> getUnreadCount() {
        return notificationService.countUnreadNotifications();
    }

    @PutMapping("/{id}/acquitter")
    public Mono<NotificationDTO> acquitter(
            @PathVariable String id,
            @RequestParam String userId) {

        return notificationService.acquitterNotification(id, userId);
    }
}