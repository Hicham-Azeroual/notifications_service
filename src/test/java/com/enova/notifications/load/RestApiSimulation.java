package com.enova.notifications.load;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Test de charge sur les endpoints REST classiques.
 *
 * Lance avec : mvn gatling:test -Dgatling.simulationClass=com.enova.notifications.load.RestApiSimulation
 * Rapport généré dans : target/gatling/
 */
public class RestApiSimulation extends Simulation {

    // =========================================================
    // Configuration HTTP de base
    // =========================================================

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8081")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling-LoadTest/1.0");

    // =========================================================
    // Scénario 1 — Lecture simple (GET /notifications)
    // =========================================================

    private final ScenarioBuilder listeNotifications = scenario("Liste des notifications")
            .exec(
                http("GET /notifications")
                    .get("/api/notifications")
                    .check(status().is(200))
            )
            .pause(1);

    // =========================================================
    // Scénario 2 — Compteur non lues (GET /count)
    // =========================================================

    private final ScenarioBuilder countNotifications = scenario("Compteur non lues")
            .exec(
                http("GET /notifications/count")
                    .get("/api/notifications/count")
                    .check(status().is(200))
                    .check(bodyString().exists())
            )
            .pause(1);

    // =========================================================
    // Scénario 3 — Mixte (lecture + count) — simule un utilisateur réel
    // =========================================================

    private final ScenarioBuilder utilisateurReel = scenario("Utilisateur réel")
            .exec(
                http("GET /notifications")
                    .get("/api/notifications")
                    .check(status().is(200))
            )
            .pause(2)
            .exec(
                http("GET /count")
                    .get("/api/notifications/count")
                    .check(status().is(200))
            )
            .pause(3);

    // =========================================================
    // Définition de la charge
    // =========================================================

    {
        setUp(
            // Scénario principal : montée progressive jusqu'à 50 utilisateurs simultanés
            utilisateurReel.injectOpen(
                atOnceUsers(5),                         // 5 users dès le départ
                rampUsers(20).during(10),               // monte à 20 en 10 secondes
                constantUsersPerSec(5).during(30),      // 5 users/sec pendant 30 secondes
                rampUsers(50).during(20)                // pic à 50 users en 20 secondes
            ),
            // Scénario secondaire : charge constante sur /count
            countNotifications.injectOpen(
                constantUsersPerSec(10).during(60)      // 10 req/sec pendant 60 secondes
            )
        )
        .protocols(httpProtocol)
        .assertions(
            // Critères de succès — le test échoue si ces seuils ne sont pas respectés
            global().responseTime().percentile(95).lt(500),   // P95 < 500ms
            global().responseTime().percentile(99).lt(1000),  // P99 < 1 seconde
            global().successfulRequests().percent().gt(99.0)  // 99% de succès minimum
        );
    }
}
