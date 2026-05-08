package com.enova.notifications.core.service;

import com.enova.notifications.core.dto.NotificationDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

// SOLUTION — CopyOnWriteArraySet pour supporter plusieurs onglets
@Service
@Slf4j
public class SsePushService {

    // userId → Set de Sinks (support multi-onglets)
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Sinks.Many<ServerSentEvent<NotificationDTO>>>> userSinks =
            new ConcurrentHashMap<>();

    // groupKey (role|etablId) → Set de Sinks (support broadcast)
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Sinks.Many<ServerSentEvent<NotificationDTO>>>> groupSinks =
            new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<NotificationDTO>> subscribe(
            String userId, String role, String etablissementId) {

        Sinks.Many<ServerSentEvent<NotificationDTO>> sink =
                Sinks.many().multicast().onBackpressureBuffer(256);

        // ADD au lieu de PUT — supporte plusieurs onglets
        userSinks.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(sink);

        // Index par groupe — pour le broadcast par role+etablissement
        String groupKey = role + "|" + etablissementId;
        groupSinks.computeIfAbsent(groupKey, k -> new CopyOnWriteArraySet<>()).add(sink);

        log.info("SSE connecte : userId={} group={} total={}",
                userId, groupKey, countActive());

        Flux<ServerSentEvent<NotificationDTO>> heartbeat =
                Flux.interval(Duration.ofSeconds(25))
                        .map(i -> ServerSentEvent.<NotificationDTO>builder()
                                .comment("heartbeat").build());

        return sink.asFlux()
                .mergeWith(heartbeat)
                .doFinally(signal -> cleanup(userId, groupKey, sink));
    }

    public Mono<Void> push(NotificationDTO dto) {


        System.out.println("Notification reçue : " + dto);
        return Mono.fromRunnable(() -> {
            if (dto.getDestinataires() != null && !dto.getDestinataires().isEmpty()) {
                // CAS 1 : destinataires précis — O(1) via userSinks
                dto.getDestinataires().forEach(userId -> {
                    var sinks = userSinks.get(userId);
                    if (sinks != null) {
                        sinks.forEach(sink -> emitToSink(sink, dto, userId));
                    }
                });
            } else if (dto.getRoleCible() != null && dto.getEtablissementId() != null) {
                // CAS 2 : broadcast par groupe — MANQUAIT dans ton code
                String groupKey = dto.getRoleCible() + "|" + dto.getEtablissementId();
                var sinks = groupSinks.get(groupKey);
                if (sinks != null) {
                    sinks.forEach(sink -> emitToSink(sink, dto, "broadcast:" + groupKey));
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void emitToSink(
            Sinks.Many<ServerSentEvent<NotificationDTO>> sink,
            NotificationDTO dto,
            String userId) {

        Sinks.EmitResult result = sink.tryEmitNext(
                ServerSentEvent.<NotificationDTO>builder()
                        .event("notification")
                        .data(dto)
                        .build()
        );
        if (result.isFailure()) {
            log.warn("Emit echec userId={} result={}", userId, result);
        }
    }

    private void cleanup(
            String userId,
            String groupKey,
            Sinks.Many<ServerSentEvent<NotificationDTO>> sink) {

        sink.tryEmitComplete();

        userSinks.computeIfPresent(userId, (k, set) -> {
            set.remove(sink);
            return set.isEmpty() ? null : set;
        });
        groupSinks.computeIfPresent(groupKey, (k, set) -> {
            set.remove(sink);
            return set.isEmpty() ? null : set;
        });
        log.info("SSE deconnecte : userId={} group={} restants={}", userId, groupKey, countActive());
    }

    public int countActive() {
        return userSinks.values().stream()
                .mapToInt(CopyOnWriteArraySet::size).sum();
    }
}