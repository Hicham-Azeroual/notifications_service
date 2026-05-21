package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.broker.event.ModuleEmetteur;
import com.enova.notifications.core.model.NotificationDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Processor spécifique au module LABO.
 *
 * Logique métier propre au labo :
 * - Extraire et valider les valeurs de référence (normal/anormal)
 * - Enrichir le titre avec l'indicateur d'anomalie
 * - Forcer le niveau CRITIQUE si la valeur dépasse le seuil critique
 */
@Component
@Slf4j
public class LaboNotificationProcessor extends DefaultNotificationProcessor {

    @Override
    public ModuleEmetteur supports() {
        return ModuleEmetteur.LABO;
    }

    @Override
    public Mono<NotificationDocument> process(GenericNotificationEvent event) {
        return Mono.fromCallable(() -> {

            Map<String, Object> donnees = event.getDonneesMetier();
            String niveauGravite = event.getNiveauGravite();
            String titre = event.getTitre();

            // Logique spécifique labo : vérifier si la valeur dépasse le seuil critique
            if (donnees != null) {
                Object valeur = donnees.get("valeurMesuree");
                Object seuil  = donnees.get("seuilCritique");

                if (valeur != null && seuil != null) {
                    try {
                        double v = Double.parseDouble(String.valueOf(valeur));
                        double s = Double.parseDouble(String.valueOf(seuil));

                        if (v > s) {
                            // Escalade automatique du niveau de gravité
                            niveauGravite = "CRITIQUE";
                            titre = "[ANOMALIE CRITIQUE] " + titre;
                            log.warn("LABO : valeur {} dépasse seuil {} — escalade CRITIQUE", v, s);
                        }
                    } catch (NumberFormatException e) {
                        log.debug("LABO : valeurs non numériques, pas d'escalade automatique");
                    }
                }
            }

            Map<String, String> rawMetadata = toStringMap(donnees);
            Map<String, String> metadata = rawMetadata != null ? new HashMap<>(rawMetadata) : new HashMap<>();
            metadata.put("processedBy", "LaboNotificationProcessor");

            return NotificationDocument.builder()
                    .id(UUID.randomUUID())
                    .createdAt(Instant.now())
                    .type(niveauGravite)
                    .typeAlerte(event.getTypeAlerte())
                    .titre(titre)
                    .message(event.getMessage())
                    .module(event.getModuleEmetteur() != null ? event.getModuleEmetteur().name() : null)
                    .etablissementId(event.getEtablissementId())
                    .roleCible(event.getRoleCible())
                    .destinataires(event.getDestinatairesSpecifiques())
                    .metadata(metadata)
                    .lue(false)
                    .acquittee(false)
                    .resolue(false)
                    .build();
        });
    }
}