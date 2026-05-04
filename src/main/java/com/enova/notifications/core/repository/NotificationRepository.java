package com.enova.notifications.core.repository;

import com.enova.notifications.core.model.NotificationDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface NotificationRepository
        extends ReactiveMongoRepository<NotificationDocument, String > {
    // Charger le panel ( badge + liste )
    Flux< NotificationDocument > findByEtablissementIdAndRoleCibleAndLueFalseOrderByCreatedAtDesc (
            String etablId , String role );
    // Compteur badge
    Mono< Long > countByLueFalse ();
    // Deduplication
    Mono < Boolean > existsByModuleAndTypeAlerteAndEtablissementIdAndAcquitteeFalse (
            String module , String typeAlerte , String etablId );
}
