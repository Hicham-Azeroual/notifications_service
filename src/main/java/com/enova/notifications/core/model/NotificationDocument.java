package com.enova.notifications.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// SOLUTION — @Builder.Default obligatoire avec Lombok
@Document(collection = "notifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDocument {

    @Id
    private String id;
    private String type;
    private String typeAlerte;
    private String titre;
    private String message;
    private String module;
    private String etablissementId;
    private String roleCible;
    private List<String> destinataires;

    // @Builder.Default : garantit la valeur même avec le pattern Builder
    @Builder.Default
    private Boolean lue       = false;

    @Builder.Default
    private Boolean acquittee = false;

    @Builder.Default
    private Boolean resolue   = false;

    private Map<String, Object> metadata;

    @CreatedDate
    private LocalDateTime createdAt;
    private LocalDateTime lueAt;
    private LocalDateTime acquitteeAt;
    private String acquittePar;

    @Indexed(expireAfterSeconds = 7_776_000) // TTL 90 jours
    private LocalDateTime expiresAt;
}
