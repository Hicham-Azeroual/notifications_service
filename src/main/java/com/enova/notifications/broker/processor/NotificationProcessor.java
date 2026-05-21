package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.broker.event.ModuleEmetteur;
import com.enova.notifications.core.model.NotificationDocument;
import reactor.core.publisher.Mono;

/**
 * Strategy : chaque module métier peut avoir sa propre logique de traitement.
 * Implémenter cette interface = ajouter un nouveau module sans toucher au consumer.
 */
public interface NotificationProcessor {

    /**
     * Le module que ce processor gère.
     * Retourner null signifie que ce processor est le handler par défaut (fallback).
     */
    ModuleEmetteur supports();

    /**
     * Transforme l'événement brut en document prêt à être persisté.
     * C'est ici que chaque module peut enrichir, valider, ou transformer ses données.
     */
    Mono<NotificationDocument> process(GenericNotificationEvent event);
}