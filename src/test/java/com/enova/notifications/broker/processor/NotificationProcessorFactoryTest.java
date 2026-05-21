package com.enova.notifications.broker.processor;

import com.enova.notifications.broker.event.ModuleEmetteur;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationProcessorFactoryTest {

    private NotificationProcessorFactory factory;
    private DefaultNotificationProcessor defaultProcessor;
    private LaboNotificationProcessor laboProcessor;

    @BeforeEach
    void setUp() {
        defaultProcessor = new DefaultNotificationProcessor();
        laboProcessor    = new LaboNotificationProcessor();
        factory          = new NotificationProcessorFactory(List.of(defaultProcessor, laboProcessor));
    }

    @Test
    void getProcessor_returnsLaboProcessor_forLabo() {
        NotificationProcessor result = factory.getProcessor(ModuleEmetteur.LABO);
        assertThat(result).isInstanceOf(LaboNotificationProcessor.class);
    }

    @Test
    void getProcessor_returnsDefault_forPharmacie() {
        NotificationProcessor result = factory.getProcessor(ModuleEmetteur.PHARMACIE);
        assertThat(result).isInstanceOf(DefaultNotificationProcessor.class);
    }

    @Test
    void getProcessor_returnsDefault_forUnknown() {
        NotificationProcessor result = factory.getProcessor(ModuleEmetteur.UNKNOWN);
        assertThat(result).isInstanceOf(DefaultNotificationProcessor.class);
    }

    @Test
    void getProcessor_returnsDefault_forNull() {
        NotificationProcessor result = factory.getProcessor(null);
        assertThat(result).isInstanceOf(DefaultNotificationProcessor.class);
    }

    @Test
    void getProcessor_returnsDefault_forUrgences() {
        // URGENCES n'a pas de processor spécifique → fallback
        NotificationProcessor result = factory.getProcessor(ModuleEmetteur.URGENCES);
        assertThat(result).isInstanceOf(DefaultNotificationProcessor.class);
    }

    @Test
    void constructor_throwsException_whenNoDefaultProcessorProvided() {
        // Sans DefaultNotificationProcessor → IllegalStateException au démarrage
        assertThatThrownBy(() ->
                new NotificationProcessorFactory(List.of(laboProcessor))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("DefaultNotificationProcessor");
    }

    @Test
    void constructor_handlesConflict_withMergeFunction() {
        // Deux processors pour le même module → premier gagne, pas de crash
        LaboNotificationProcessor labo2 = new LaboNotificationProcessor();

        assertThat(new NotificationProcessorFactory(
                List.of(defaultProcessor, laboProcessor, labo2)
        ).getProcessor(ModuleEmetteur.LABO)).isNotNull();
    }
}
