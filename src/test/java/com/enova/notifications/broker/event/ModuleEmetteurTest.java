package com.enova.notifications.broker.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleEmetteurTest {

    @Test
    void from_returnsCorrectEnum_forKnownValues() {
        assertThat(ModuleEmetteur.from("PHARMACIE")).isEqualTo(ModuleEmetteur.PHARMACIE);
        assertThat(ModuleEmetteur.from("LABO")).isEqualTo(ModuleEmetteur.LABO);
        assertThat(ModuleEmetteur.from("URGENCES")).isEqualTo(ModuleEmetteur.URGENCES);
        assertThat(ModuleEmetteur.from("CRITIQUE")).isEqualTo(ModuleEmetteur.CRITIQUE);
    }

    @Test
    void from_isCaseInsensitive() {
        assertThat(ModuleEmetteur.from("pharmacie")).isEqualTo(ModuleEmetteur.PHARMACIE);
        assertThat(ModuleEmetteur.from("Labo")).isEqualTo(ModuleEmetteur.LABO);
    }

    @Test
    void from_returnsUnknown_forUnrecognizedValue() {
        // module inconnu depuis RabbitMQ → UNKNOWN, pas d'exception Jackson
        assertThat(ModuleEmetteur.from("MODULE_INCONNU")).isEqualTo(ModuleEmetteur.UNKNOWN);
        assertThat(ModuleEmetteur.from("XYZ")).isEqualTo(ModuleEmetteur.UNKNOWN);
    }

    @Test
    void from_returnsUnknown_forNull() {
        assertThat(ModuleEmetteur.from(null)).isEqualTo(ModuleEmetteur.UNKNOWN);
    }

    @Test
    void from_returnsUnknown_forEmptyString() {
        assertThat(ModuleEmetteur.from("")).isEqualTo(ModuleEmetteur.UNKNOWN);
    }
}
