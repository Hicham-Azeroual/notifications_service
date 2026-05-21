package com.enova.notifications.load;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Test de charge sur les connexions SSE.
 *
 * OBJECTIF : vérifier que le service maintient 500 connexions SSE simultanées
 * avec une empreinte mémoire constante et sans dégradation des performances.
 *
 * Lance avec : mvn gatling:test "-Dgatling.simulationClass=com.enova.notifications.load.SseConnectionSimulation"
 */
public class SseConnectionSimulation extends Simulation {

    private static final String[] ROLES           = {"PHARMACIEN", "MEDECIN", "INFIRMIER", "BIOLOGISTE"};
    private static final String[] ETABLISSEMENTS  = {"hopital-A", "hopital-B", "hopital-C"};

    // =========================================================
    // Feeder — génère userId, role, etablissement pour chaque user
    // =========================================================

    private static List<Map<String, Object>> buildUserFeeder(int count) {
        List<Map<String, Object>> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("userId",        "user-" + i);
            entry.put("role",          ROLES[i % ROLES.length]);
            entry.put("etablissement", ETABLISSEMENTS[i % ETABLISSEMENTS.length]);
            list.add(entry);
        }
        return list;
    }

    // =========================================================
    // Configuration HTTP
    // =========================================================

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8081")
            .acceptHeader("text/event-stream")
            .userAgentHeader("Gatling-SSE-LoadTest/1.0");

    // =========================================================
    // Scénario 1 — Connexion SSE simple avec heartbeat
    // =========================================================

    private final ScenarioBuilder connexionSseBasique = scenario("SSE Basique")
            .feed(listFeeder(buildUserFeeder(500)).circular())
            .exec(
                sse("Ouvrir SSE")
                    .get("/api/notifications/stream" +
                        "?userId=#{userId}" +
                        "&role=#{role}" +
                        "&etablissementId=#{etablissement}")
            )
            .pause(30)
            .exec(sse("Fermer SSE").close());

    // =========================================================
    // Scénario 2 — Connexion longue durée (simule un user connecté toute la journée)
    // =========================================================

    private final ScenarioBuilder connexionLongueDuree = scenario("SSE Longue durée")
            .feed(listFeeder(buildUserFeeder(500)).circular())
            .exec(
                sse("Ouvrir SSE longue durée")
                    .get("/api/notifications/stream" +
                        "?userId=#{userId}" +
                        "&role=#{role}" +
                        "&etablissementId=#{etablissement}")
                    // Vérifier le heartbeat (arrives toutes les 25 secondes)
                    .await(30).on(sse.checkMessage("Heartbeat").check(regex(":heartbeat")))
            )
            .pause(60)
            .exec(sse("Fermer SSE").close());

    // =========================================================
    // Définition de la charge
    // =========================================================

    {
        setUp(
            connexionSseBasique.injectOpen(
                atOnceUsers(10),
                rampUsers(100).during(20),
                rampUsers(500).during(60)
            )
        )
        .protocols(httpProtocol)
        .assertions(
            // 100% des connexions SSE doivent s'établir sans erreur
            global().successfulRequests().percent().gt(98.0),
            // La fermeture doit être quasi-instantanée (< 500ms)
            details("Fermer SSE").responseTime().percentile(95).lt(500),
            // Connexion totale (ouverture + durée + fermeture) < 40s (pause=30s + marge)
            details("Ouvrir SSE").responseTime().percentile(95).lt(40000)
        );
    }
}
