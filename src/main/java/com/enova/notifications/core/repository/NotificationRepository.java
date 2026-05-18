package com.enova.notifications.core.repository;

import com.enova.notifications.core.model.NotificationDocument;
import org.springframework.data.cassandra.repository.AllowFiltering;
import org.springframework.data.cassandra.repository.ReactiveCassandraRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface NotificationRepository
        extends ReactiveCassandraRepository<NotificationDocument, UUID> {

    // Index secondaire sur etablissementId+roleCible — ALLOW FILTERING acceptable
    // car Cassandra filtre d'abord sur les index avant de scanner
    @AllowFiltering
    Flux<NotificationDocument> findByEtablissementIdAndRoleCibleAndLueFalse(
            String etablissementId, String roleCible);

    @AllowFiltering
    Mono<Long> countByLueFalse();

    // findById(UUID) est hérité de ReactiveCassandraRepository — utilisé par acquitter
}
