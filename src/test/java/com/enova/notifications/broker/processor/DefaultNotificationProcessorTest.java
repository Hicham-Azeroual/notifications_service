package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import com.enova.notifications.broker.event.ModuleEmetteur;
import com.enova.notifications.core.model.NotificationDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultNotificationProcessorTest {

    private DefaultNotificationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DefaultNotificationProcessor();
    }

    @Test
    void supports_returnsNull_isDefaultFallback() {
        assertThat(processor.supports()).isNull();
    }

    @Test
    void process_createsDocumentWithCorrectFields() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.PHARMACIE)
                .titre("Rupture Amoxicilline 500mg")
                .message("Stock à 0. Action requise.")
                .niveauGravite("CRITIQUE")
                .typeAlerte("RUPTURE_STOCK")
                .etablissementId("hopital-A")
                .roleCible("PHARMACIEN")
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> {
                    assertThat(doc.getId()).isNotNull();
                    assertThat(doc.getCreatedAt()).isNotNull();
                    assertThat(doc.getTitre()).isEqualTo("Rupture Amoxicilline 500mg");
                    assertThat(doc.getMessage()).isEqualTo("Stock à 0. Action requise.");
                    assertThat(doc.getType()).isEqualTo("CRITIQUE");
                    assertThat(doc.getTypeAlerte()).isEqualTo("RUPTURE_STOCK");
                    assertThat(doc.getModule()).isEqualTo("PHARMACIE");
                    assertThat(doc.getEtablissementId()).isEqualTo("hopital-A");
                    assertThat(doc.getRoleCible()).isEqualTo("PHARMACIEN");
                    assertThat(doc.getLue()).isFalse();
                    assertThat(doc.getAcquittee()).isFalse();
                    assertThat(doc.getResolue()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void process_withDestinatairesSpecifiques_mapsCorrectly() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.URGENCES)
                .titre("Alerte urgence")
                .message("Code rouge")
                .niveauGravite("CRITIQUE")
                .typeAlerte("CODE_ROUGE")
                .destinatairesSpecifiques(List.of("user-1", "user-2"))
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> {
                    assertThat(doc.getDestinataires()).containsExactly("user-1", "user-2");
                })
                .verifyComplete();
    }

    @Test
    void process_withDonneesMetier_convertsToStringMap() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .moduleEmetteur(ModuleEmetteur.PHARMACIE)
                .titre("Test")
                .donneesMetier(Map.of("stockActuel", 0, "seuilMinimum", 50))
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> {
                    assertThat(doc.getMetadata()).isNotNull();
                    assertThat(doc.getMetadata()).containsKey("stockActuel");
                    assertThat(doc.getMetadata().get("stockActuel")).isEqualTo("0");
                })
                .verifyComplete();
    }

    @Test
    void process_withNullModuleEmetteur_doesNotThrow() {
        GenericNotificationEvent event = GenericNotificationEvent.builder()
                .titre("Test")
                .message("Message")
                .build();

        StepVerifier.create(processor.process(event))
                .assertNext(doc -> assertThat(doc.getModule()).isNull())
                .verifyComplete();
    }
}
