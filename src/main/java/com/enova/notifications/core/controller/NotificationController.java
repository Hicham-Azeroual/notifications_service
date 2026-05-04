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
@RequiredArgsConstructor // Permet d'injecter automatiquement les services sans écrire @Autowired
public class NotificationController {

    // J'utilise le NotificationService que nous avons créé précédemment
    // pour garder ton code propre (Clean Architecture).
    private final NotificationService notificationService;
    private final SsePushService ssePushService;

    // =========================================================
    // TEMPS RÉEL (SSE)
    // =========================================================

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<NotificationDTO>> stream(
            @RequestParam String userId,
            @RequestParam String role,
            @RequestParam String etablissementId) {

        return ssePushService.subscribe(userId, role, etablissementId);
    }

    // =========================================================
    // API REST CLASSIQUES
    // =========================================================

    // Charger toutes les notifications existantes
    @GetMapping
    public Flux<NotificationDTO> getNotifications() {
        System.out.println("endpoint getNotifications is called");

        return notificationService.getAllNotifications();
    }

    // Compteur pour le badge rouge
    @GetMapping("/count")
    public Mono<Long> getUnreadCount() {

        return notificationService.countUnreadNotifications();
    }

    // Acquitter une notification
    @PutMapping("/{id}/acquitter")
    public Mono<NotificationDTO> acquitter(
            @PathVariable String id,
            @RequestParam String userId) {

        return notificationService.acquitterNotification(id, userId);
    }
}