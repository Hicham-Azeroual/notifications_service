package com.enova.notifications.core.service;

import com.enova.notifications.core.dto.NotificationDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
@Slf4j
public class SsePushService {

    // userId → Set de Sinks (support multi-onglets)
    private final Map<String, Set<Sinks.Many<ServerSentEvent<NotificationDTO>>>> userSinks =
            new ConcurrentHashMap<>();

    // groupKey ("role|etablId") → Set de Sinks (inverted index broadcast)
    private final Map<String, Set<Sinks.Many<ServerSentEvent<NotificationDTO>>>> groupSinks =
            new ConcurrentHashMap<>();



    public Flux<ServerSentEvent<NotificationDTO>> subscribe(
            String userId, String role, String etablissementId) {

        Sinks.Many<ServerSentEvent<NotificationDTO>> sink =
                Sinks.many().multicast().onBackpressureBuffer(256);

        userSinks.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(sink);

        String groupKey = role + "|" + etablissementId;
        groupSinks.computeIfAbsent(groupKey, k -> new CopyOnWriteArraySet<>()).add(sink);

        log.info("SSE connecte : userId={} group={} total={}", userId, groupKey, countActive());

        Flux<ServerSentEvent<NotificationDTO>> heartbeat =
                Flux.interval(Duration.ofSeconds(25))
                        .map(i -> ServerSentEvent.<NotificationDTO>builder()
                                .comment("heartbeat").build());

        return sink.asFlux()
                .mergeWith(heartbeat)
                .doFinally(signal -> cleanup(userId, groupKey, sink));
    }

    public void push(NotificationDTO dto) {
        if (dto.getDestinataires() != null && !dto.getDestinataires().isEmpty()) {
            dto.getDestinataires().forEach(userId -> {
                var sinks = userSinks.get(userId);
                if (sinks != null) sinks.forEach(sink -> emitToSink(sink, dto, userId));
            });
        } else if (dto.getRoleCible() != null && dto.getEtablissementId() != null) {
            String groupKey = dto.getRoleCible() + "|" + dto.getEtablissementId();
            var sinks = groupSinks.get(groupKey);
            if (sinks != null) sinks.forEach(sink -> emitToSink(sink, dto, "broadcast:" + groupKey));
        }
    }

    private void emitToSink(Sinks.Many<ServerSentEvent<NotificationDTO>> sink,
                             NotificationDTO dto, String target) {
        log.debug("Emit notification target={} dto={}", target, dto);

        Sinks.EmitResult result = sink.tryEmitNext(
                ServerSentEvent.<NotificationDTO>builder()
                        .event("notification")
                        .data(dto)
                        .build()
        );
        if (result.isFailure()) {
            log.warn("Emit echec target={} result={}", target, result);
        }
    }

    private void cleanup(String userId, String groupKey,
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
        return userSinks.values().stream().mapToInt(Set::size).sum();
    }
}