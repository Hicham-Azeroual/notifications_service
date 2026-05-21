package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.ModuleEmetteur;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NotificationProcessorFactory {

    private final Map<ModuleEmetteur, NotificationProcessor> processors;
    private final NotificationProcessor defaultProcessor;

    // amélioration 3 : log une seule fois par module inconnu
    private final Set<String> unknownLogged = ConcurrentHashMap.newKeySet();

    public NotificationProcessorFactory(List<NotificationProcessor> allProcessors) {
        // amélioration 1 : (a, b) -> a évite le crash si deux processors déclarent le même module
        // amélioration 2 : clé Enum au lieu de String
        this.processors = allProcessors.stream()
                .filter(p -> p.supports() != null)
                .collect(Collectors.toMap(
                        NotificationProcessor::supports,
                        Function.identity(),
                        (a, b) -> {
                            log.warn("Conflit de processor pour module={} — {} ignoré au profit de {}",
                                    a.supports(), b.getClass().getSimpleName(), a.getClass().getSimpleName());
                            return a;
                        }
                ));

        this.defaultProcessor = allProcessors.stream()
                .filter(p -> p.supports() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Aucun DefaultNotificationProcessor trouvé dans le contexte Spring"));

        log.info("NotificationProcessorFactory initialisée — processors spécifiques : {}", processors.keySet());
    }

    public NotificationProcessor getProcessor(ModuleEmetteur module) {
        if (module == null || module == ModuleEmetteur.UNKNOWN) {
            // amélioration 3 : log seulement si c'est UNKNOWN (valeur inconnue reçue du JSON)
            if (module == ModuleEmetteur.UNKNOWN) {
                log.warn("Module inconnu reçu — utilisation du processor par défaut");
            }
            return defaultProcessor;
        }

        NotificationProcessor processor = processors.get(module);

        if (processor != null) {
            return processor;
        }

        // amélioration 3 : log une seule fois par module sans processor spécifique
        if (unknownLogged.add(module.name())) {
            log.info("Pas de processor spécifique pour module={} — processor par défaut utilisé", module);
        }

        return defaultProcessor;
    }
}