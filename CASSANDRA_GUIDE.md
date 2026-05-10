# Guide Complet Apache Cassandra
## Pour comprendre et utiliser Cassandra dans un projet Spring Boot

---

# 1. C'est quoi Cassandra ?

Apache Cassandra est une base de données **NoSQL distribuée** conçue par Facebook en 2008,
aujourd'hui open-source. Elle est utilisée par Netflix, Apple, Instagram, Uber.

**Son objectif principal :** écrire et lire des millions de données par seconde,
sans jamais tomber, même si plusieurs serveurs tombent en même temps.

## Comparaison rapide

| Critère              | MySQL / PostgreSQL     | MongoDB                | Cassandra               |
|----------------------|------------------------|------------------------|-------------------------|
| Type                 | Relationnel (SQL)      | Document (NoSQL)       | Colonnes larges (NoSQL) |
| Schéma               | Fixe (ALTER TABLE)     | Flexible               | Semi-fixe               |
| Langage de requête   | SQL                    | MQL / Aggregation      | CQL (proche du SQL)     |
| Jointures            | Oui (JOIN)             | Non (embed ou $lookup) | Non (jamais)            |
| WHERE libre          | Oui                    | Oui                    | Non — clé primaire only |
| Scalabilité          | Vertical               | Horizontal             | Horizontal natif        |
| Point fort           | Requêtes complexes     | Flexibilité schéma     | Écriture massive, HA    |
| Point faible         | Scalabilité limitée    | Consistency variable   | Requêtes limitées       |

---

# 2. Les Concepts Fondamentaux

## 2.1 Keyspace

Le **keyspace** est l'équivalent d'une base de données (database en SQL, database en MongoDB).

```sql
-- SQL
CREATE DATABASE hospital;

-- MongoDB
use hospital

-- Cassandra
CREATE KEYSPACE hospital
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
```

Le paramètre `replication_factor` dit combien de copies des données existent sur le cluster.
En production on met 3 (si un serveur tombe, 2 autres ont les données).

## 2.2 Table

La **table** est similaire aux autres bases, sauf que sa conception est critique en Cassandra.

```sql
-- SQL
CREATE TABLE notifications (
    id SERIAL PRIMARY KEY,
    message TEXT,
    created_at TIMESTAMP
);

-- Cassandra (CQL)
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    message TEXT,
    created_at TIMESTAMP
);
```

## 2.3 La Clé Primaire — Le Concept le Plus Important

En Cassandra, la clé primaire a deux rôles distincts :

```
PRIMARY KEY = (Partition Key) + [Clustering Key]
               ─────────────   ────────────────
               Obligatoire      Optionnel
```

### Partition Key
- Détermine sur quel **nœud du cluster** la donnée est stockée
- Toutes les lignes avec la même partition key sont sur le même nœud
- Permet les requêtes ultra-rapides (O(1))

### Clustering Key
- Détermine l'**ordre** des données à l'intérieur d'une partition
- Permet le tri natif (ASC ou DESC)

### Exemple visuel

```
Cluster Cassandra (3 nœuds)
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│   Nœud 1    │  │   Nœud 2    │  │   Nœud 3    │
│             │  │             │  │             │
│ etab="H001" │  │ etab="H002" │  │ etab="H003" │
│ role="PHARM"│  │ role="MED"  │  │ role="LABO" │
└─────────────┘  └─────────────┘  └─────────────┘
```

Quand on cherche `WHERE etablissement_id='H001' AND role_cible='PHARM'`,
Cassandra sait **directement** sur quel nœud aller. Pas de scan !

## 2.4 Types de Clés Primaires

### Simple (une seule colonne)
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,   -- partition key = id
    name TEXT,
    email TEXT
);
```

### Composite (partition key + clustering key)
```sql
CREATE TABLE notifications_by_group (
    etablissement_id TEXT,
    role_cible       TEXT,
    created_at       TIMESTAMP,
    id               UUID,
    message          TEXT,
    PRIMARY KEY ((etablissement_id, role_cible), created_at, id)
    --           ─────────────────────────────  ──────────────
    --           Partition Key composite         Clustering Keys
) WITH CLUSTERING ORDER BY (created_at DESC);
```

Ce modèle permet :
```sql
-- Requête ultra-rapide (utilise la partition key)
SELECT * FROM notifications_by_group
WHERE etablissement_id = 'H001' AND role_cible = 'PHARMACIEN';

-- Résultat déjà trié par created_at DESC (clustering key)
```

---

# 3. La Règle d'Or de Cassandra

> **"Design your tables around your queries, not your data."**
> Conçois tes tables autour de tes requêtes, pas autour de tes données.

## En SQL / MongoDB (approche classique)

1. Tu modélises tes données (normalisation)
2. Tu écris tes requêtes après

```sql
-- SQL : tu peux faire WHERE sur n'importe quelle colonne
SELECT * FROM notifications WHERE module = 'PHARMACIE' ORDER BY created_at DESC;
SELECT * FROM notifications WHERE lue = false;
SELECT * FROM notifications WHERE id = 5;
-- Tout marche, SQL gère tout
```

## En Cassandra (approche query-driven)

1. Tu listes tes requêtes
2. Tu crées une table pour chaque requête

```sql
-- Requête 1 : "Donne-moi les notifs non lues pour un groupe"
-- → Table avec partition key (etablissement_id, role_cible)

-- Requête 2 : "Trouve une notif par son ID"
-- → Table avec partition key (id) OU index secondaire sur id

-- Requête 3 : "Compte les notifs non lues d'un groupe"
-- → Même table que Requête 1
```

## Ce que Cassandra INTERDIT sans index

```sql
-- INTERDIT — lue n'est pas dans la clé primaire
SELECT * FROM notifications WHERE lue = false;
-- Erreur : Cannot execute this query as it might involve data filtering

-- AUTORISÉ avec ALLOW FILTERING (mais lent sur grande table)
SELECT * FROM notifications WHERE lue = false ALLOW FILTERING;

-- AUTORISÉ si index secondaire sur lue
CREATE INDEX ON notifications (lue);
SELECT * FROM notifications WHERE lue = false;
```

---

# 4. CQL — Cassandra Query Language

CQL ressemble beaucoup à SQL mais avec des limitations importantes.

## 4.1 Opérations de base

```sql
-- Créer un keyspace
CREATE KEYSPACE sih_notifications
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- Utiliser un keyspace
USE sih_notifications;

-- Créer une table
CREATE TABLE notifications (
    id               UUID PRIMARY KEY,
    titre            TEXT,
    message          TEXT,
    module           TEXT,
    etablissement_id TEXT,
    role_cible       TEXT,
    lue              BOOLEAN,
    acquittee        BOOLEAN,
    created_at       TIMESTAMP,
    metadata         MAP<TEXT, TEXT>,
    destinataires    LIST<TEXT>
);

-- Insérer
INSERT INTO notifications (id, titre, message, module, lue, created_at)
VALUES (uuid(), 'Rupture stock', 'Paracétamol épuisé', 'PHARMACIE', false, toTimestamp(now()));

-- Insérer AVEC TTL (la ligne disparaît automatiquement après 90 jours)
INSERT INTO notifications (id, titre, message, lue, created_at)
VALUES (uuid(), 'Alerte', 'Message', false, toTimestamp(now()))
USING TTL 7776000;  -- 90 jours en secondes

-- Sélectionner
SELECT * FROM notifications WHERE id = 550e8400-e29b-41d4-a716-446655440000;

-- Mettre à jour
UPDATE notifications SET lue = true, lue_at = toTimestamp(now())
WHERE id = 550e8400-e29b-41d4-a716-446655440000;

-- Supprimer
DELETE FROM notifications WHERE id = 550e8400-e29b-41d4-a716-446655440000;

-- Compter
SELECT COUNT(*) FROM notifications WHERE etablissement_id = 'H001' ALLOW FILTERING;
```

## 4.2 Types de données Cassandra

```sql
-- Types simples
UUID        -- identifiant unique (comme ObjectId de MongoDB)
TEXT        -- chaîne de caractères
INT         -- entier
BIGINT      -- grand entier
BOOLEAN     -- true/false
TIMESTAMP   -- date + heure
FLOAT       -- décimal
DOUBLE      -- décimal précis

-- Types collections (NoSQL)
LIST<TEXT>              -- ['PHARM_001', 'PHARM_002']
SET<TEXT>               -- {'READ', 'WRITE'} — sans doublons
MAP<TEXT, TEXT>         -- {'cle': 'valeur', 'stock': '0'}
MAP<TEXT, INT>          -- {'stock': 0, 'seuil': 10}
```

## 4.3 Index Secondaires

```sql
-- Créer un index pour autoriser WHERE sur une colonne non-PK
CREATE INDEX ON notifications (etablissement_id);
CREATE INDEX ON notifications (role_cible);
CREATE INDEX ON notifications (lue);

-- Maintenant ces requêtes sont possibles
SELECT * FROM notifications WHERE etablissement_id = 'H001';
SELECT * FROM notifications WHERE lue = false;
```

**Attention :** les index secondaires sont moins performants que la partition key.
À utiliser pour des colonnes avec **haute cardinalité** (beaucoup de valeurs différentes).
À éviter pour des colonnes avec **basse cardinalité** (boolean, statut avec 2-3 valeurs).

## 4.4 ALLOW FILTERING

```sql
-- Cassandra refuse par défaut les requêtes qui scannent toute la table
SELECT * FROM notifications WHERE lue = false;
-- Erreur !

-- ALLOW FILTERING force Cassandra à accepter (mais peut être lent)
SELECT * FROM notifications WHERE lue = false ALLOW FILTERING;

-- Acceptable si la requête porte déjà sur une partition connue
SELECT * FROM notifications
WHERE etablissement_id = 'H001'   -- partition connue → scan limité
AND role_cible = 'PHARMACIEN'
AND lue = false
ALLOW FILTERING;                  -- filtre lue dans cette seule partition = OK
```

---

# 5. Spring Data Cassandra

## 5.1 Les annotations du modèle

```java
@Table("notifications")           // ← @Document en MongoDB
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationDocument {

    @Id                           // ← même annotation, mais UUID au lieu de String
    private UUID id;

    @Column("etablissement_id")   // ← nom de colonne en snake_case (convention Cassandra)
    @Indexed                      // ← crée un CREATE INDEX sur cette colonne
    private String etablissementId;

    @Column("role_cible")
    @Indexed
    private String roleCible;

    @Column private String titre;
    @Column private String message;
    @Column private String module;

    @Column private Boolean lue;
    @Column private Boolean acquittee;

    @Column("created_at")
    private Instant createdAt;    // Instant au lieu de LocalDateTime pour Cassandra

    @Column
    private Map<String, String> metadata;  // Map<String, Object> interdit en Cassandra
                                           // Cassandra ne connaît pas le type Object
    @Column
    private List<String> destinataires;
}
```

## 5.2 ReactiveCassandraRepository — Pour les opérations CRUD classiques

```java
@Repository
public interface NotificationRepository
        extends ReactiveCassandraRepository<NotificationDocument, UUID> {
    //                                                              ^^^
    //                                    Type de l'@Id — UUID (pas String comme MongoDB)

    // Spring génère le CQL automatiquement à partir du nom de la méthode
    // exactement comme avec MongoDB

    // findBy... → SELECT * FROM notifications WHERE ...
    @AllowFiltering
    Flux<NotificationDocument> findByEtablissementIdAndRoleCibleAndLueFalse(
            String etablissementId, String roleCible);
    // CQL généré :
    // SELECT * FROM notifications WHERE etablissement_id=? AND role_cible=? AND lue=false ALLOW FILTERING

    // countBy... → SELECT COUNT(*) FROM notifications WHERE ...
    @AllowFiltering
    Mono<Long> countByEtablissementIdAndRoleCibleAndLueFalse(
            String etablissementId, String roleCible);

    // @Query pour écrire le CQL manuellement si besoin
    @Query("SELECT * FROM notifications WHERE id = ?0")
    Mono<NotificationDocument> findByNotifId(UUID id);
}
```

### Méthodes héritées automatiquement

```java
// Ces méthodes existent sans les déclarer (héritées de ReactiveCassandraRepository)
repository.findById(uuid)               // Mono<NotificationDocument>
repository.findAll()                    // Flux<NotificationDocument>
repository.save(document)              // Mono<NotificationDocument>
repository.deleteById(uuid)             // Mono<Void>
repository.count()                      // Mono<Long>
repository.existsById(uuid)             // Mono<Boolean>
```

## 5.3 ReactiveCassandraOperations — Pour les opérations avancées

`ReactiveCassandraOperations` (appelé aussi `cassandraTemplate`) est un niveau plus bas
que le repository. On l'utilise quand on a besoin d'options que le repository ne supporte pas.

### Cas d'usage 1 : INSERT avec TTL

```java
@Autowired
private ReactiveCassandraOperations cassandraTemplate;

// Insert normal via repository (pas de TTL)
repository.save(document);
// CQL : INSERT INTO notifications (...) VALUES (...)

// Insert avec TTL via template
InsertOptions options = InsertOptions.builder()
        .ttl(Duration.ofDays(90))
        .build();
cassandraTemplate.insert(document, options);
// CQL : INSERT INTO notifications (...) VALUES (...) USING TTL 7776000
//                                                    ^^^^^^^^^^^^^^^^
//                                              Cassandra supprime après 90 jours
```

### Cas d'usage 2 : Requête CQL personnalisée

```java
// Requête CQL libre avec le template
Flux<NotificationDocument> results = cassandraTemplate.select(
    Query.query(Criteria.where("module").is("PHARMACIE")),
    NotificationDocument.class
);
```

### Cas d'usage 3 : Batch (plusieurs inserts en une fois)

```java
// Insérer plusieurs documents en une seule opération
cassandraTemplate.batchOps()
    .insert(doc1)
    .insert(doc2)
    .insert(doc3)
    .execute();
```

---

# 6. Repository vs Template — Quand utiliser quoi ?

```
┌─────────────────────────────────────────────────────────────┐
│ ReactiveCassandraRepository                                  │
│                                                              │
│ ✅ findById, save, delete, findAll                           │
│ ✅ Méthodes dérivées (findByX, countByX)                     │
│ ✅ @Query pour CQL custom simple                             │
│                                                              │
│ Utilise pour : Controller, Service                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ ReactiveCassandraOperations (cassandraTemplate)              │
│                                                              │
│ ✅ INSERT avec TTL                                           │
│ ✅ INSERT avec timestamp custom                              │
│ ✅ Batch operations                                          │
│ ✅ Requêtes CQL très complexes                               │
│                                                              │
│ Utilise pour : Consumer RabbitMQ (besoin du TTL)             │
└─────────────────────────────────────────────────────────────┘
```

---

# 7. Application dans ton projet

## Sans TTL (simple) — utilise le repository partout

```java
@Component
public class CentralNotificationConsumer {

    private final NotificationRepository repository;  // ← repository classique

    public void handleAnyModuleEvent(GenericNotificationEvent event, ...) {
        Mono.just(event)
            .map(this::convertToDocument)
            .flatMap(repository::save)                // ← save normal, pas de TTL
            .map(mapper::toDto)
            .flatMap(ssePushService::push)
            .subscribe();
    }
}
```

## Avec TTL (avancé) — utilise le template pour l'insert

```java
@Component
public class CentralNotificationConsumer {

    private final ReactiveCassandraOperations cassandraTemplate;  // ← template
    private static final InsertOptions TTL_90_DAYS =
            InsertOptions.builder().ttl(Duration.ofDays(90)).build();

    public void handleAnyModuleEvent(GenericNotificationEvent event, ...) {
        Mono.just(event)
            .map(this::convertToDocument)
            .flatMap(doc -> cassandraTemplate.insert(doc, TTL_90_DAYS)
                                             .map(r -> r.getEntity()))  // ← avec TTL
            .map(mapper::toDto)
            .flatMap(ssePushService::push)
            .subscribe();
    }
}
```

---

# 8. Démarrage avec Docker

## docker-compose.yml

```yaml
services:
  cassandra:
    image: cassandra:4.1
    ports:
      - "9042:9042"
    healthcheck:
      test: ["CMD-SHELL", "cqlsh -e 'describe keyspaces'"]
      interval: 30s
      retries: 10
      start_period: 90s

  cassandra-init:          # Crée le keyspace au démarrage
    image: cassandra:4.1
    depends_on:
      cassandra:
        condition: service_healthy
    volumes:
      - ./src/main/resources/schema.cql:/schema.cql:ro
    command: cqlsh sih-cassandra -f /schema.cql
    restart: "no"
```

## application.yml Spring Boot

```yaml
spring:
  cassandra:
    contact-points: localhost     # adresse du serveur Cassandra
    port: 9042                    # port par défaut
    keyspace-name: sih_notifications
    local-datacenter: datacenter1  # doit correspondre à CASSANDRA_DC dans Docker
    schema-action: CREATE_IF_NOT_EXISTS  # crée les tables automatiquement en dev
```

## Tester avec cqlsh (terminal dans le container)

```bash
# Entrer dans le container
docker exec -it sih-cassandra cqlsh

# Commandes utiles
DESCRIBE KEYSPACES;
USE sih_notifications;
DESCRIBE TABLES;
DESCRIBE TABLE notifications;
SELECT * FROM notifications LIMIT 10;
SELECT COUNT(*) FROM notifications;
SELECT * FROM notifications WHERE id = <uuid>;
```

---

# 9. Erreurs fréquentes et solutions

## Erreur 1 : Data filtering
```
InvalidQueryException: Cannot execute this query as it might involve data filtering
```
**Cause :** WHERE sur une colonne qui n'est pas clé primaire et pas indexée.
**Solution :** Ajouter `@Indexed` sur la colonne OU ajouter `@AllowFiltering` sur la méthode du repository.

## Erreur 2 : Keyspace does not exist
```
InvalidQueryException: Keyspace 'sih_notifications' does not exist
```
**Cause :** Le keyspace n'a pas été créé avant le démarrage.
**Solution :** Exécuter le `schema.cql` ou lancer le service `cassandra-init` Docker.

## Erreur 3 : No viable alternative
```
SyntaxException: line 1:X no viable alternative at input 'Object'
```
**Cause :** `Map<String, Object>` utilisé — Cassandra ne supporte pas le type `Object`.
**Solution :** Utiliser `Map<String, String>` et sérialiser les valeurs complexes en String.

## Erreur 4 : Connection refused port 9042
```
AllNodesFailedException: Could not reach any contact point
```
**Cause :** Spring Boot démarre avant que Cassandra soit prête (Cassandra prend 60-90s).
**Solution :** Ajouter dans `application.yml` :
```yaml
spring:
  cassandra:
    request:
      timeout: 10s
    connection:
      connect-timeout: 10s
      init-query-timeout: 10s
```

---

# 10. Récapitulatif des annotations

| Annotation | Package | Rôle |
|---|---|---|
| `@Table("nom")` | `cassandra.core.mapping` | Marque la classe comme table Cassandra |
| `@Id` | `springframework.data.annotation` | Clé primaire (UUID) |
| `@Column("nom")` | `cassandra.core.mapping` | Mappe un champ à une colonne |
| `@Indexed` | `cassandra.core.mapping` | Crée un index secondaire sur la colonne |
| `@PrimaryKeyColumn` | `cassandra.core.mapping` | Colonne de clé primaire composite |
| `@PrimaryKeyClass` | `cassandra.core.mapping` | Classe représentant une clé composite |
| `@AllowFiltering` | `cassandra.repository` | Autorise ALLOW FILTERING sur la méthode |
| `@Query("CQL")` | `cassandra.repository` | CQL manuel pour une méthode du repository |
