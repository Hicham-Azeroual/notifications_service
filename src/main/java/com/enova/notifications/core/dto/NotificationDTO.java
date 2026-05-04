package com.enova.notifications.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDTO {

    private String id;

    // Contenu affiché à l'utilisateur
    private String type;
    private String titre;
    private String message;
    private String module;

    // Ciblage (Optionnel : souvent le front n'a pas besoin de savoir à qui d'autre c'est envoyé,
    // mais je les laisse si tu en as besoin pour ton interface)
    private String etablissementId;
    private String roleCible;
    private List<String> destinataires;

    // États du cycle de vie
    private Boolean lue;
    private Boolean acquittee;
    private Boolean resolue;

    // Métadonnées spécifiques à chaque module
    private Map<String, Object> metadata;

    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime lueAt;
    private LocalDateTime acquitteeAt;
    private String acquittePar;

    // expiresAt n'est généralement pas utile pour le frontend, tu peux l'omettre
    // ou le laisser selon ton besoin métier.
    private LocalDateTime expiresAt;
}