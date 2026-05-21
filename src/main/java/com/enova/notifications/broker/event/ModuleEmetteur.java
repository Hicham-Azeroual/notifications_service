package com.enova.notifications.broker.event;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ModuleEmetteur {
    PHARMACIE,
    URGENCES,
    LABO,
    CRITIQUE,
    UNKNOWN;

    // Désérialisation JSON safe : "LABO" → LABO, "XYZ" → UNKNOWN (pas d'exception)
    @JsonCreator
    public static ModuleEmetteur from(String value) {
        if (value == null) return UNKNOWN;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}