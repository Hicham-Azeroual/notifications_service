package com.enova.notifications.broker.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenericNotificationEvent {
    private String moduleEmetteur ; // " PHARMACIE ", " URGENCES ", " LABO "
    private String typeAlerte ; // " RUPTURE_STOCK ", " CODE_ROUGE "
    private String niveauGravite ; // " INFO ", " WARNING " , " CRITIQUE "
    private String titre ; // " Rupture de stock : P a r a c t a m o l "
    private String message ; // " Le stock est 0. Action requise ."
    // Le ciblage
    private String etablissementId ;
    private String roleCible ; // " PHARMACIEN ", " MEDECIN_GARDE "
    private List< String > destinatairesSpecifiques ; // Nullable
    // La magie du NoSQL : Un dictionnaire flexible pour les d o n n e s m t i e r
    private Map< String , Object > donneesMetier ;
}
