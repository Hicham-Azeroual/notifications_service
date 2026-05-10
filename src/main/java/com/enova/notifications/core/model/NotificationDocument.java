package com.enova.notifications.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Indexed;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Table("notifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDocument {

    @Id
    private UUID id;

    @Column private String type;
    @Column("type_alerte")  private String typeAlerte;
    @Column private String titre;
    @Column private String message;
    @Column private String module;

    // Index secondaires — permettent les requêtes par groupe
    @Indexed
    @Column("etablissement_id") private String etablissementId;

    @Indexed
    @Column("role_cible")       private String roleCible;

    @Column private List<String> destinataires;

    @Builder.Default @Column private Boolean lue       = false;
    @Builder.Default @Column private Boolean acquittee = false;
    @Builder.Default @Column private Boolean resolue   = false;

    // Map<String,String> car Cassandra ne supporte pas Map<String,Object> nativement
    @Column private Map<String, String> metadata;

    @Column("created_at")    private Instant createdAt;
    @Column("lue_at")        private Instant lueAt;
    @Column("acquittee_at")  private Instant acquitteeAt;
    @Column("acquitte_par")  private String  acquittePar;
}
