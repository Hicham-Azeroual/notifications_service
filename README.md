# Service de Notification Centralisé — SIH Enova

Microservice de notification temps réel pour les systèmes d'information hospitaliers (SIH).  
Conçu pour diffuser des alertes critiques (ruptures de stock, codes rouges, résultats de laboratoire) aux professionnels de santé connectés, sans latence perceptible.

---

## Contexte du projet

Dans un environnement hospitalier multi-établissements, plusieurs modules métier (pharmacie, urgences, laboratoire) génèrent des événements critiques qui doivent parvenir aux bons professionnels en temps réel. Ce service centralise la réception, la persistance et la diffusion de ces notifications.

Ce POC a été développé dans le cadre d'un stage de fin d'études chez **Enova**.

---

## Architecture générale

```
Modules métier (Pharmacie, Urgences, Labo)
        │
        │  publie un GenericNotificationEvent
        ▼
  ┌─────────────┐
  │  RabbitMQ   │  Topic Exchange : sih.events
  │  CloudAMQP  │  Queues : pharmacie / urgence / labo / critique
  └──────┬──────┘
         │  consommé par
         ▼
┌────────────────────────────────────┐
│   CentralNotificationConsumer      │
│   - Reçoit l'événement             │
│   - Persiste en base (Cassandra)   │
│   - Push SSE temps réel            │
└────────────┬───────────────────────┘
             │
    ┌────────┴────────┐
    │                 │
    ▼                 ▼
┌──────────┐   ┌──────────────────────┐
│ Astra DB │   │   SsePushService     │
│ Cassandra│   │   Inverted Index     │
│ (audit)  │   │   en mémoire (RAM)   │
└──────────┘   └──────────┬───────────┘
                           │  Server-Sent Events
                           ▼
               Navigateurs clients connectés
```

---

## Flux de traitement

```
1. Un module publie sur RabbitMQ          → garantie de livraison (ACK/NACK)
2. Le Consumer reçoit l'événement          → traitement réactif (WebFlux)
3. Persistance dans Cassandra             → historique 90 jours (TTL)
4. Push SSE vers les clients connectés    → temps réel < 50ms
5. Heartbeat toutes les 25s              → maintien de la connexion SSE
```

---

## Technologies utilisées

### Spring Boot 4 + WebFlux (Reactive)

**Pourquoi :** Un système de notification temps réel avec potentiellement des milliers de connexions SSE simultanées. Le modèle bloquant (Spring MVC) alloue un thread par connexion — avec 1000 utilisateurs connectés, cela représente 1000 threads en attente. WebFlux utilise un modèle non-bloquant avec un nombre fixe de threads, ce qui permet de gérer un très grand nombre de connexions avec une empreinte mémoire minimale.

**Pourquoi pas Spring MVC :** Incompatible avec les connexions SSE longue durée à grande échelle.

---

### RabbitMQ (CloudAMQP)

**Pourquoi :** Le découplage entre les modules émetteurs (pharmacie, urgences) et le service de notification est fondamental. RabbitMQ garantit qu'une alerte critique n'est jamais perdue même si le service de notification redémarre. Le pattern Dead Letter Queue (DLX) assure que les messages en erreur sont capturés pour analyse.

**Architecture des queues :**
```
Exchange : sih.events (Topic)
├── pharmacie.#     → queue.notif.pharmacie
├── urgence.#       → queue.notif.urgence
├── labo.#          → queue.notif.labo
└── #.critique      → queue.notif.critique (toutes les alertes critiques)

Dead Letter Exchange : dlx.sih
└── erreur.#        → queue.notif.dead-letter
```

**Pourquoi pas Kafka :** Pour ce POC, RabbitMQ est suffisant et plus simple à opérer. Kafka serait pertinent si le volume dépasse plusieurs millions d'événements par jour ou si la relecture de l'historique des événements est nécessaire.

---

### Server-Sent Events (SSE)

**Pourquoi :** Le frontend a besoin de recevoir des notifications sans polling (interrogation périodique du serveur). SSE est une connexion HTTP unidirectionnelle (serveur → client) persistante, native dans tous les navigateurs modernes, sans dépendance externe.

**Pourquoi pas WebSocket :** SSE est suffisant pour ce cas d'usage (flux unidirectionnel). WebSocket ouvre une connexion bidirectionnelle complète, plus complexe à implémenter et à scaler, sans bénéfice supplémentaire ici.

**Pourquoi pas le polling :** Le polling génère une charge constante sur la base de données (requête toutes les N secondes par client connecté), introduit une latence artificielle, et ne passe pas à l'échelle.

---

### Inverted Index en mémoire

**Pourquoi :** Quand une notification arrive, il faut trouver instantanément tous les utilisateurs connectés qui correspondent aux critères (rôle, établissement). Interroger la base de données à chaque envoi ajouterait 50-200ms de latence et créerait une charge importante sur Cassandra.

L'inverted index maintient en RAM une structure `critère → Set<userId>`, permettant de résoudre les destinataires par intersection d'ensembles en quelques microsecondes.

```
"role:PHARMACIEN"    → {user-1, user-2, user-5}
"etab:hopital-A"     → {user-1, user-3, user-5}
intersection         → {user-1, user-5}  — sans aucune requête DB
```

---

### Apache Cassandra (Astra DB — DataStax Cloud)

**Contexte :** La solution a d'abord été développée avec **MongoDB** comme base de persistance. La migration vers Cassandra a été effectuée pour s'aligner avec les choix techniques de l'équipe qui utilise Cassandra dans ses projets de production.

**Pourquoi Cassandra pour les notifications :**
- **TTL natif** : expiration automatique des notifications après 90 jours, sans job de nettoyage
- **Scalabilité horizontale** : conçu pour un très grand volume d'écritures distribuées
- **Haute disponibilité** : pas de single point of failure

**Pourquoi Astra DB :** Instance Cassandra managée dans le cloud (DataStax), évite la complexité opérationnelle de gérer un cluster Cassandra en local ou en production. Connexion sécurisée via Secure Connect Bundle.

**Pourquoi pas MongoDB (choix initial) :** MongoDB était parfaitement adapté (TTL via `@Indexed(expireAfterSeconds)`, flexibilité du schéma). La migration vers Cassandra a été motivée par l'alignement avec les standards techniques de l'équipe, pas par une limite technique de MongoDB.

**Pourquoi pas PostgreSQL :** Le schéma des notifications est variable selon le module émetteur (`donneesMetier` est flexible). Un schéma rigide SQL aurait nécessité soit des colonnes nullable en grand nombre, soit une table de métadonnées séparée avec des jointures.

---

### Lombok + MapStruct

**Lombok :** Élimine le boilerplate (getters, setters, constructeurs, builders) sans impact sur les performances — le code est généré à la compilation.

**MapStruct :** Mapping type-safe entre `NotificationDocument` (entité Cassandra) et `NotificationDTO` (objet de transfert). Généré à la compilation — aucune réflexion à l'exécution contrairement à ModelMapper.

---

## Structure du projet

```
src/main/java/com/enova/notifications/
├── broker/
│   ├── consumer/
│   │   └── CentralNotificationConsumer.java   # Consomme RabbitMQ, orchestre le traitement
│   └── event/
│       └── GenericNotificationEvent.java       # Contrat entre modules (événement entrant)
├── config/
│   ├── AstraCassandraConfig.java               # Connexion Astra DB (Secure Connect Bundle)
│   ├── RabbitMQConfig.java                     # Déclaration exchanges, queues, bindings
│   └── CorsConfig.java                         # Configuration CORS
├── core/
│   ├── controller/
│   │   └── NotificationController.java         # Endpoints REST + SSE
│   ├── dto/
│   │   └── NotificationDTO.java                # Objet de transfert (API + SSE)
│   ├── mapper/
│   │   └── NotificationMapper.java             # Document → DTO (MapStruct)
│   ├── model/
│   │   └── NotificationDocument.java           # Entité Cassandra
│   ├── repository/
│   │   └── NotificationRepository.java         # Reactive Cassandra Repository
│   └── service/
│       ├── NotificationService.java            # Logique métier (CRUD, acquittement)
│       └── SsePushService.java                 # Inverted index + push SSE temps réel
└── exception/
    └── GlobalExceptionHandler.java
```

---

## API Endpoints

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/notifications/stream` | Connexion SSE temps réel |
| `GET` | `/api/notifications` | Liste de toutes les notifications |
| `GET` | `/api/notifications/count` | Nombre de notifications non lues |
| `PUT` | `/api/notifications/{id}/acquitter` | Acquitter une notification |

**Paramètres SSE :**
```
GET /api/notifications/stream?userId=user-123&role=PHARMACIEN&etablissementId=hopital-A
```

---

## Format d'événement RabbitMQ

Les modules métier publient un `GenericNotificationEvent` sur l'exchange `sih.events` :

```json
{
  "moduleEmetteur": "PHARMACIE",
  "typeAlerte": "RUPTURE_STOCK",
  "niveauGravite": "CRITIQUE",
  "titre": "Rupture de stock : Amoxicilline 500mg",
  "message": "Le stock est à 0. Action requise immédiatement.",
  "etablissementId": "hopital-A",
  "roleCible": "PHARMACIEN",
  "destinatairesSpecifiques": null,
  "donneesMetier": {
    "medicamentId": "AMX-500",
    "stockActuel": 0,
    "seuilMinimum": 50
  }
}
```

---

## Lancer le projet

**Prérequis :**
- Java 21
- Maven 3.9+
- Compte Astra DB (DataStax) avec keyspace `sih_notifications`
- Instance RabbitMQ (ou CloudAMQP)

**Configuration `application.yml` :**
```yaml
spring:
  cassandra:
    username: <CLIENT_ID_ASTRA>
    password: <CLIENT_SECRET_ASTRA>
    schema-action: CREATE_IF_NOT_EXISTS
  rabbitmq:
    host: <RABBITMQ_HOST>
    username: <RABBITMQ_USER>
    password: <RABBITMQ_PASSWORD>
```

Placer le Secure Connect Bundle dans `src/main/resources/secure-connect-sih-notifications.zip`.

```bash
mvn spring-boot:run
```

Le service démarre sur le port **8081**.

---

## Améliorations prévues

### 1. Ciblage multi-critères (Inverted Index étendu)

**Problème actuel :** Le ciblage supporte un seul rôle et un seul établissement par notification.

**Solution :** Étendre l'inverted index pour supporter des combinaisons complexes :
```
"Tous les pharmaciens de l'hôpital A en garde de nuit"
→ intersection de : profil:PHARMACIEN ∩ etab:hopital-A ∩ groupe:garde-nuit
```

Cela nécessite d'enrichir `GenericNotificationEvent` avec des `Set<String>` pour chaque critère (`profils`, `services`, `equipes`, `groupes`) et d'ajouter une logique AND/OR dans le `SsePushService`.

---

### 2. Support multi-instances (Redis Pub/Sub)

**Problème actuel :** L'inverted index est en mémoire locale — si le service est déployé sur plusieurs instances, un utilisateur connecté à l'instance 2 ne reçoit pas les notifications publiées sur l'instance 1.

**Solution :** Utiliser Redis Pub/Sub comme canal de coordination inter-instances. Chaque instance publie sur un canal Redis. Toutes les instances reçoivent et poussent vers leurs sinks SSE locaux. La persistance (Cassandra) garantit qu'aucune donnée n'est perdue si un message Redis est manqué.

```
RabbitMQ → Consumer → Cassandra → Redis PUBLISH
                                        ↓
                           toutes les instances
                                        ↓
                        chaque instance → SSE clients locaux
```

---

### 3. Redesign du schéma Cassandra pour les requêtes ordonnées

**Problème actuel :** `findAll()` ne peut pas retourner les notifications triées par date côté Cassandra (limitation du modèle de données actuel — `created_at` n'est pas une clustering key).

**Solution :** Redesigner la table avec une partition bucket + `created_at` comme clustering key :
```sql
PRIMARY KEY ((bucket), created_at, id)
CLUSTERING ORDER BY (created_at DESC)
```

Ce changement rend le tri natif côté Cassandra, sans tri en mémoire.

---

### 4. Authentification et autorisation

Intégrer un filtre de sécurité sur l'endpoint SSE pour valider le token JWT de l'utilisateur avant d'établir la connexion. Le `userId` et le rôle doivent être extraits du token, pas passés en paramètre URL.

---

### 5. Pagination des notifications

Ajouter la pagination sur `GET /api/notifications` pour éviter de charger l'intégralité de l'historique en une seule requête.

---

## Choix non retenus

| Option écartée | Raison |
|---|---|
| WebSocket | Bidirectionnel inutile — le flux est unidirectionnel (serveur → client) |
| Polling HTTP | Latence artificielle + charge DB constante |
| Kafka | Surdimensionné pour ce volume — complexité opérationnelle non justifiée |
| Indexation Cassandra sur tous les champs | Scatter-gather sur tous les nœuds — latence élevée et non scalable |
| Spring MVC | Incompatible avec les connexions SSE longue durée à grande échelle |
| ModelMapper | Réflexion à l'exécution — moins performant que MapStruct (génération compile-time) |

---

## Auteur

**Hicham Azeroual** — Stagiaire ingénieur chez Enova  
hicham.azeroual@enova.ma