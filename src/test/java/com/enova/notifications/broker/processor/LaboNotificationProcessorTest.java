package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.broker.event.ModuleEmetteur;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LaboNotificationProcessorTest {

    private LaboNotificationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new LaboNotificationProcessor();
    }

    @Test
    void supports_returnsLabo() {
        assertThat(processor.supports()).isEqualTo(ModuleEmetteur.LABO);
    }

    @Test
    void process_escalatesToCritique_whenValueExceedsThreshold() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.LABO)
                .titre("Résultat glycémie")
                .message("Valeur anormale détectée")
                .niveauGravite("WARNING")
                .typeAlerte("RESULTAT_ANORMAL")
                .etablissementId("hopital-A")
                .roleCible("MEDECIN")
                .donneesMetier(Map.of("valeurMesuree", "15.5", "seuilCritique", "10.0"))
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> {
                    assertThat(doc.getType()).isEqualTo("CRITIQUE");
                    assertThat(doc.getTitre()).startsWith("[ANOMALIE CRITIQUE]");
                    assertThat(doc.getMetadata()).containsEntry("processedBy", "LaboNotificationProcessor");
                })
                .verifyComplete();
    }

    @Test
    void process_doesNotEscalate_whenValueBelowThreshold() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.LABO)
                .titre("Résultat glycémie")
                .message("Valeur normale")
                .niveauGravite("INFO")
                .typeAlerte("RESULTAT_NORMAL")
                .donneesMetier(Map.of("valeurMesuree", "5.0", "seuilCritique", "10.0"))
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> {
                    assertThat(doc.getType()).isEqualTo("INFO");
                    assertThat(doc.getTitre()).doesNotStartWith("[ANOMALIE CRITIQUE]");
                })
                .verifyComplete();
    }

    @Test
    void process_doesNotEscalate_whenValueEqualsThreshold() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.LABO)
                .titre("Résultat limite")
                .niveauGravite("WARNING")
                .donneesMetier(Map.of("valeurMesuree", "10.0", "seuilCritique", "10.0"))
                .build();

        // valeur == seuil → pas d'escalade (condition strictement supérieure)
        StepVerifier.create(processor.process(event))
                .assertNext(doc -> assertThat(doc.getType()).isEqualTo("WARNING"))
                .verifyComplete();
    }

    @Test
    void process_handlesNonNumericValues_gracefully() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.LABO)
                .titre("Résultat bactériologie")
                .niveauGravite("INFO")
                .donneesMetier(Map.of("valeurMesuree", "POSITIF", "seuilCritique", "N/A"))
                .build();

        // Valeurs non numériques → pas d'escalade, pas d'exception
        StepVerifier.create(processor.process(event))
                .assertNext(doc -> assertThat(doc.getType()).isEqualTo("INFO"))
                .verifyComplete();
    }

    @Test
    void process_withoutDonneesMetier_doesNotThrow() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.LABO)
                .titre("Résultat sans données")
                .niveauGravite("INFO")
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> assertThat(doc.getTitre()).isEqualTo("Résultat sans données"))
                .verifyComplete();
    }
}
