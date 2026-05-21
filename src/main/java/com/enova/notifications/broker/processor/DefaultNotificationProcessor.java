package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.broker.event.ModuleEmetteur;
import com.enova.notifications.core.model.NotificationDocument;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Processor par défaut — utilisé pour tous les modules sans logique spécifique.
 * supports() retourne null → la Factory l'utilise comme fallback.
 */
@Component
public class DefaultNotificationProcessor implements NotificationProcessor {

    @Override
    public ModuleEmetteur supports() {
        return null; // fallback pour tout module non reconnu
    }

    @Override
    public Mono<NotificationDocument> process(GenericNotificationEvent event) {
        return Mono.fromCallable(() -> NotificationDocument.builder()
                .id(UUID.randomUUID())
                .createdAt(Instant.now())
                .type(event.getNiveauGravite())
                .typeAlerte(event.getTypeAlerte())
                .titre(event.getTitre())
                .message(event.getMessage())
                .module(event.getModuleEmetteur() != null ? event.getModuleEmetteur().name() : null)
                .etablissementId(event.getEtablissementId())
                .roleCible(event.getRoleCible())
                .destinataires(event.getDestinatairesSpecifiques())
                .metadata(toStringMap(event.getDonneesMetier()))
                .lue(false)
                .acquittee(false)
                .resolue(false)
                .build());
    }

    protected Map<String, String> toStringMap(Map<String, Object> source) {
        if (source == null) return null;
        return source.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    }
}