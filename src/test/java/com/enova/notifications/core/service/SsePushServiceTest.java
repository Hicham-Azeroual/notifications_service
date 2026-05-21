package com.enova.notifications.core.service;

import com.enova.notifications.core.dto.NotificationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SsePushServiceTest {

    private SsePushService ssePushService;

    @BeforeEach
    void setUp() {
        ssePushService = new SsePushService();
    }

    // =========================================================
    // SUBSCRIBE — Inverted Index
    // =========================================================

    @Test
    void subscribe_incrementsActiveCount() {
        Disposable sub = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A").subscribe();
        assertThat(ssePushService.countActive()).isEqualTo(1);
        sub.dispose();
    }

    @Test
    void subscribe_multipleUsers_countIsCorrect() {
        Disposable sub1 = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A").subscribe();
        Disposable sub2 = ssePushService.subscribe("user-2", "MEDECIN", "hopital-A").subscribe();
        Disposable sub3 = ssePushService.subscribe("user-3", "PHARMACIEN", "hopital-B").subscribe();

        assertThat(ssePushService.countActive()).isEqualTo(3);

        sub1.dispose();
        sub2.dispose();
        sub3.dispose();
    }

    @Test
    void subscribe_sameUser_multipleOnglets_countIsCorrect() {
        // Même userId, deux onglets ouverts
        Disposable sub1 = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A").subscribe();
        Disposable sub2 = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A").subscribe();

        assertThat(ssePushService.countActive()).isEqualTo(2);

        sub1.dispose();
        sub2.dispose();
    }

    @Test
    void cleanup_decrementCount_onDispose() {
        Disposable sub = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A").subscribe();
        assertThat(ssePushService.countActive()).isEqualTo(1);

        sub.dispose();

        assertThat(ssePushService.countActive()).isEqualTo(0);
    }

    // =========================================================
    // PUSH — ciblage par groupe
    // =========================================================

    @Test
    void push_deliversToMatchingGroup() {
        NotificationDTO dto = buildDto("PHARMACIEN", "hopital-A", null);

        StepVerifier.create(
                ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A")
                        .filter(sse -> "notification".equals(sse.event()))
                        .next()
        )
        .then(() -> ssePushService.push(dto))
        .assertNext(sse -> {
            assertThat(sse.data()).isNotNull();
            assertThat(sse.data().getTitre()).isEqualTo("Test notification");
        })
        .thenCancel()
        .verify(Duration.ofSeconds(2));
    }

    @Test
    void push_doesNotDeliver_toDifferentRole() {
        NotificationDTO dto = buildDto("MEDECIN", "hopital-A", null);

        List<ServerSentEvent<NotificationDTO>> received = new ArrayList<>();
        Disposable sub = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A")
                .filter(sse -> "notification".equals(sse.event()))
                .subscribe(received::add);

        ssePushService.push(dto);

        assertThat(received).isEmpty();
        sub.dispose();
    }

    @Test
    void push_doesNotDeliver_toDifferentEtablissement() {
        NotificationDTO dto = buildDto("PHARMACIEN", "hopital-B", null);

        List<ServerSentEvent<NotificationDTO>> received = new ArrayList<>();
        Disposable sub = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A")
                .filter(sse -> "notification".equals(sse.event()))
                .subscribe(received::add);

        ssePushService.push(dto);

        assertThat(received).isEmpty();
        sub.dispose();
    }

    @Test
    void push_deliversToSpecificDestinataire() {
        NotificationDTO dto = NotificationDTO.builder()
                .id("notif-1")
                .titre("Message direct")
                .destinataires(List.of("user-1"))
                .build();

        StepVerifier.create(
                ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A")
                        .filter(sse -> "notification".equals(sse.event()))
                        .next()
        )
        .then(() -> ssePushService.push(dto))
        .assertNext(sse -> assertThat(sse.data().getTitre()).isEqualTo("Message direct"))
        .thenCancel()
        .verify(Duration.ofSeconds(2));
    }

    @Test
    void push_withNullRoleCible_doesNotThrow() {
        // dto sans role ni destinataires → aucun push, pas d'exception
        NotificationDTO dto = NotificationDTO.builder().id("notif-1").titre("Test").build();

        List<ServerSentEvent<NotificationDTO>> received = new ArrayList<>();
        Disposable sub = ssePushService.subscribe("user-1", "PHARMACIEN", "hopital-A")
                .filter(sse -> "notification".equals(sse.event()))
                .subscribe(received::add);

        ssePushService.push(dto); // ne doit pas lever d'exception

        assertThat(received).isEmpty();
        sub.dispose();
    }

    // =========================================================
    // Utilitaire
    // =========================================================

    private NotificationDTO buildDto(String role, String etab, List<String> destinataires) {
        return NotificationDTO.builder()
                .id("notif-1")
                .titre("Test notification")
                .roleCible(role)
                .etablissementId(etab)
                .destinataires(destinataires)
                .build();
    }
}
