package com.enova.notifications.core.service;

import com.enova.notifications.core.dto.NotificationDTO;
import com.enova.notifications.core.mapper.NotificationMapper;
import com.enova.notifications.core.model.NotificationDocument;
import com.enova.notifications.core.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository repository;
    @Mock private NotificationMapper mapper;
    @Mock private SsePushService ssePushService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, mapper, ssePushService);
    }

    // =========================================================
    // countUnreadNotifications
    // =========================================================

    @Test
    void countUnreadNotifications_returnsRepositoryCount() {
        when(repository.countByLueFalse()).thenReturn(Mono.just(5L));

        StepVerifier.create(service.countUnreadNotifications())
                .assertNext(count -> assertThat(count).isEqualTo(5L))
                .verifyComplete();

        verify(repository).countByLueFalse();
    }

    // =========================================================
    // acquitterNotification
    // =========================================================

    @Test
    void acquitterNotification_setsAcquitteeAndLue() {
        UUID id = UUID.randomUUID();
        NotificationDocument notif = NotificationDocument.builder()
                .id(id)
                .titre("Rupture stock")
                .lue(false)
                .acquittee(false)
                .resolue(false)
                .createdAt(Instant.now())
                .build();

        NotificationDTO dto = NotificationDTO.builder()
                .id(id.toString())
                .titre("Rupture stock")
                .acquittee(true)
                .lue(true)
                .acquittePar("user-1")
                .build();

        when(repository.findById(id)).thenReturn(Mono.just(notif));
        when(repository.save(any())).thenReturn(Mono.just(notif));
        when(mapper.toDto(any())).thenReturn(dto);
        doNothing().when(ssePushService).push(any());

        StepVerifier.create(service.acquitterNotification(id.toString(), "user-1"))
                .assertNext(result -> {
                    assertThat(result.getAcquittee()).isTrue();
                    assertThat(result.getLue()).isTrue();
                    assertThat(result.getAcquittePar()).isEqualTo("user-1");
                })
                .verifyComplete();

        verify(repository).save(argThat(doc ->
                doc.getAcquittee() &&
                doc.getLue() &&
                "user-1".equals(doc.getAcquittePar()) &&
                doc.getAcquitteeAt() != null
        ));
    }

    @Test
    void acquitterNotification_throwsError_whenAlreadyAcquittee() {
        UUID id = UUID.randomUUID();
        NotificationDocument notif = NotificationDocument.builder()
                .id(id)
                .acquittee(true) // déjà acquittée
                .build();

        when(repository.findById(id)).thenReturn(Mono.just(notif));

        StepVerifier.create(service.acquitterNotification(id.toString(), "user-1"))
                .expectError(RuntimeException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void acquitterNotification_throwsError_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.acquitterNotification(id.toString(), "user-1"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void acquitterNotification_pushSseAfterSave() {
        UUID id = UUID.randomUUID();
        NotificationDocument notif = NotificationDocument.builder()
                .id(id).acquittee(false).lue(false).resolue(false)
                .createdAt(Instant.now()).build();
        NotificationDTO dto = NotificationDTO.builder().id(id.toString()).build();

        when(repository.findById(id)).thenReturn(Mono.just(notif));
        when(repository.save(any())).thenReturn(Mono.just(notif));
        when(mapper.toDto(any())).thenReturn(dto);
        doNothing().when(ssePushService).push(any());

        StepVerifier.create(service.acquitterNotification(id.toString(), "user-1"))
                .expectNextCount(1)
                .verifyComplete();

        // Le push SSE est déclenché après l'acquittement
        verify(ssePushService).push(dto);
    }
}
