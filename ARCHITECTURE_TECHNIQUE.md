# Architecture Technique — Détails d'Implémentation
## Service de Notification Centralisé — SIH Enova

Ce document couvre en détail les choix d'architecture, les algorithmes, et les solutions
techniques discutées et implémentées dans le cadre du microservice de notification.

---

## Table des matières

1. [Inverted Index en mémoire](#1-inverted-index-en-mémoire)
2. [Redis Pub/Sub — Support multi-instances](#2-redis-pubsub--support-multi-instances)
3. [Comparaison des approches — Big O Notation](#3-comparaison-des-approches--big-o-notation)
4. [Arbre d'expressions booléennes AND/OR/NOT](#4-arbre-dexpressions-booléennes-andornot)
5. [Cassandra — Modèle de données, limitations, redesign](#5-cassandra--modèle-de-données-limitations-redesign)
6. [Synthèse et roadmap d'évolution](#6-synthèse-et-roadmap-dévolution)

---

## 1. Inverted Index en mémoire

### Le problème

Quand une notification arrive (via RabbitMQ), il faut répondre à la question :
> "Quels utilisateurs connectés doivent recevoir cette notification ?"

La notification cible un `roleCible` (ex: `PHARMACIEN`) et un `etablissementId` (ex: `hopital-A`).

**Approche naïve :** interroger Cassandra à chaque notification reçue pour trouver les utilisateurs.
- Latence : 50–200ms par requête DB
- Charge : 1 requête DB × nombre de notifications par seconde
- Non scalable

### La solution : Inverted Index

Un **inverted index** (index inversé) est une structure de données issue du monde des moteurs
de recherche (Elasticsearch, Lucene). Au lieu de partir d'un document pour trouver ses mots-clés,
on part d'un mot-clé pour trouver tous les documents qui le contiennent.

Ici, on l'adapte : au lieu de partir d'un utilisateur pour trouver ses critères, on part d'un
critère pour trouver instantanément tous les utilisateurs qui y correspondent.

### Structure de données

```
groupSinks : Map<String, CopyOnWriteArraySet<String>>
    "role:PHARMACIEN"        → { "user-1", "user-2", "user-5" }
    "role:MEDECIN"           → { "user-3" }
    "etab:hopital-A"         → { "user-1", "user-3", "user-5" }
    "etab:hopital-B"         → { "user-2" }

userSinks : Map<String, Sinks.Many<ServerSentEvent<NotificationDTO>>>
    "user-1"  → Sink SSE (connexion active)
    "user-2"  → Sink SSE (connexion active)
    ...
```

### Algorithme de ciblage

```
Notification reçue : roleCible=PHARMACIEN, etablissementId=hopital-A

1. Récupérer groupSinks["role:PHARMACIEN"]  → { user-1, user-2, user-5 }
2. Récupérer groupSinks["etab:hopital-A"]   → { user-1, user-3, user-5 }
3. Intersection                             → { user-1, user-5 }
4. Pour chaque userId dans l'intersection :
       userSinks[userId].tryEmitNext(notificationSSE)
```

Résultat : zéro requête base de données, résolution en quelques microsecondes.

### Cycle de vie d'un utilisateur dans l'index

```
CONNEXION SSE (/stream?userId=user-1&role=PHARMACIEN&etablissementId=hopital-A)
    → Créer un Sink SSE pour user-1
    → userSinks.put("user-1", sink)
    → groupSinks["role:PHARMACIEN"].add("user-1")
    → groupSinks["etab:hopital-A"].add("user-1")

DÉCONNEXION (navigateur fermé, timeout)
    → groupSinks["role:PHARMACIEN"].remove("user-1")
    → groupSinks["etab:hopital-A"].remove("user-1")
    → userSinks.remove("user-1")
    → sink.tryEmitComplete()
```

### Code — SsePushService.java

```java
@Service
public class SsePushService {

    // userId → Sink SSE (une entrée par onglet/connexion)
    private final Map<String, CopyOnWriteArraySet<Sinks.Many<ServerSentEvent<NotificationDTO>>>>
        userSinks = new ConcurrentHashMap<>();

    // critère → Set<userId> (inverted index)
    private final Map<String, CopyOnWriteArraySet<String>>
        groupSinks = new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<NotificationDTO>> subscribe(
            String userId, String role, String etablissementId) {

        Sinks.Many<ServerSentEvent<NotificationDTO>> sink =
            Sinks.many().multicast().onBackpressureBuffer();

        // Enregistrement dans userSinks
        userSinks.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(sink);

        // Enregistrement dans l'inverted index
        groupSinks.computeIfAbsent("role:" + role, k -> new CopyOnWriteArraySet<>()).add(userId);
        groupSinks.computeIfAbsent("etab:" + etablissementId, k -> new CopyOnWriteArraySet<>()).add(userId);

        // Heartbeat toutes les 25 secondes (maintien connexion SSE)
        Flux<ServerSentEvent<NotificationDTO>> heartbeat = Flux.interval(Duration.ofSeconds(25))
            .map(i -> ServerSentEvent.<NotificationDTO>builder()
                .comment("heartbeat").build());

        return Flux.merge(sink.asFlux(), heartbeat)
            .doFinally(signal -> cleanup(userId, role, etablissementId, sink));
    }

    public void push(NotificationDTO notification) {
        Set<String> targets = resolveTargets(notification);
        targets.forEach(userId -> {
            CopyOnWriteArraySet<Sinks.Many<ServerSentEvent<NotificationDTO>>> sinks =
                userSinks.get(userId);
            if (sinks != null) {
                ServerSentEvent<NotificationDTO> event = ServerSentEvent
                    .<NotificationDTO>builder()
                    .id(notification.getId().toString())
                    .event("notification")
                    .data(notification)
                    .build();
                sinks.forEach(sink -> sink.tryEmitNext(event));
            }
        });
    }

    private Set<String> resolveTargets(NotificationDTO notification) {
        // Cas 1 : destinataires spécifiques (userId explicites)
        if (notification.getDestinataires() != null && !notification.getDestinataires().isEmpty()) {
            return new HashSet<>(notification.getDestinataires());
        }

        // Cas 2 : ciblage par groupe (intersection inverted index)
        Set<String> byRole = Optional.ofNullable(groupSinks.get("role:" + notification.getRoleCible()))
            .map(HashSet::new).orElse(new HashSet<>());
        Set<String> byEtab = Optional.ofNullable(groupSinks.get("etab:" + notification.getEtablissementId()))
            .map(HashSet::new).orElse(new HashSet<>());

        byRole.retainAll(byEtab); // intersection
        return byRole;
    }

    private void cleanup(String userId, String role, String etablissementId,
                         Sinks.Many<ServerSentEvent<NotificationDTO>> sink) {
        CopyOnWriteArraySet<Sinks.Many<...>> sinks = userSinks.get(userId);
        if (sinks != null) {
            sinks.remove(sink);
            if (sinks.isEmpty()) {
                userSinks.remove(userId);
                groupSinks.getOrDefault("role:" + role, new CopyOnWriteArraySet<>()).remove(userId);
                groupSinks.getOrDefault("etab:" + etablissementId, new CopyOnWriteArraySet<>()).remove(userId);
            }
        }
        sink.tryEmitComplete();
    }
}
```

### Pourquoi ConcurrentHashMap + CopyOnWriteArraySet ?

| Structure | Raison |
|---|---|
| `ConcurrentHashMap` | Accès concurrent thread-safe sans `synchronized` global |
| `CopyOnWriteArraySet` | Les lectures (très fréquentes) sont sans verrou — idéal pour un index lu à chaque notification |

---

## 2. Redis Pub/Sub — Support multi-instances

### Le problème du multi-instances

L'inverted index est en **mémoire locale**. Si le service est déployé sur 3 instances :

```
Instance 1 : user-1 connecté (PHARMACIEN, hopital-A)
Instance 2 : user-2 connecté (PHARMACIEN, hopital-A)
Instance 3 : user-5 connecté (PHARMACIEN, hopital-A)

RabbitMQ publie → Instance 1 consomme
Instance 1 cherche dans SON index → trouve uniquement user-1
user-2 et user-5 (sur Instance 2 et 3) ne reçoivent RIEN
```

### La solution : Redis Pub/Sub comme bus inter-instances

```
RabbitMQ → CentralNotificationConsumer (Instance 1)
                │
                ├── Persistance Cassandra (une seule fois)
                │
                └── Redis PUBLISH "channel:notifications" (broadcast)
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
         Instance 1    Instance 2    Instance 3
         (subscriber)  (subscriber)  (subscriber)
              │             │             │
         push SSE      push SSE      push SSE
         vers user-1   vers user-2   vers user-5
```

**Chaque instance subscribe à Redis et push vers ses propres sinks SSE locaux.**
La persistance Cassandra est faite une seule fois (par le consumer RabbitMQ).

### Dépendance Maven à ajouter

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

### Configuration application.yml

```yaml
spring:
  data:
    redis:
      host: redis-xxxxx.cloud.redislabs.com
      port: 12736
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
```

### RedisConfig.java

```java
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {

        RedisSerializationContext<String, String> context =
            RedisSerializationContext.<String, String>newSerializationContext(
                new StringRedisSerializer())
                .value(new StringRedisSerializer())
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }
}
```

### SsePushService modifié avec Redis

```java
@Service
public class SsePushService {

    private static final String REDIS_CHANNEL = "channel:notifications";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // ... même inverted index qu'avant ...

    @PostConstruct
    public void subscribeToRedis() {
        // Chaque instance écoute le canal Redis
        redisTemplate.listenToChannel(REDIS_CHANNEL)
            .subscribe(message -> {
                try {
                    NotificationDTO dto = objectMapper.readValue(
                        message.getMessage(), NotificationDTO.class);
                    // Push vers les sinks locaux de cette instance
                    pushLocally(dto);
                } catch (Exception e) {
                    log.error("Erreur désérialisation message Redis", e);
                }
            });
    }

    // Appelé par CentralNotificationConsumer
    public void publish(NotificationDTO notification) {
        try {
            String json = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(REDIS_CHANNEL, json).subscribe();
        } catch (Exception e) {
            log.error("Erreur publication Redis", e);
        }
    }

    private void pushLocally(NotificationDTO notification) {
        // Même logique de résolution inverted index qu'avant
        Set<String> targets = resolveTargets(notification);
        targets.forEach(userId -> {
            // ... émission SSE locale ...
        });
    }
}
```

### Rôle de Redis vs RabbitMQ — différence fondamentale

| | RabbitMQ | Redis Pub/Sub |
|---|---|---|
| **Rôle** | Transport fiable entre modules métier → notification service | Broadcast interne entre instances du notification service |
| **Garantie livraison** | Oui (ACK, DLQ, persistance) | Non (fire-and-forget) |
| **Consommateurs** | 1 seul consumer traite le message | Tous les subscribers reçoivent simultanément |
| **Cas de panne** | Message conservé dans la queue | Message perdu si une instance est down au moment du publish |
| **Pourquoi acceptable** | — | Cassandra garantit la persistance — un utilisateur qui se reconnecte récupère son historique |

---

## 3. Comparaison des approches — Big O Notation

### Contexte

Pour distribuer une notification aux bons utilisateurs connectés, 3 approches ont été évaluées.

**Variables :**
- `U_total` = nombre total d'utilisateurs connectés (ex: 1000)
- `p` = nombre de critères de ciblage (ex: 2 : rôle + établissement)
- `n` = nombre d'utilisateurs dans le groupe cible (ex: 50)
- `RTT` = latence réseau + DB (ex: 100ms)

---

### Approche 1 : Requête DB à chaque notification (naïve)

```
Pour chaque notification reçue :
    SELECT * FROM users WHERE role = ? AND etablissement = ?  → résultat : n users
    Pour chaque user : push SSE
```

| Métrique | Complexité | Valeur exemple |
|---|---|---|
| Temps CPU | O(1) côté app | négligeable |
| Latence réseau | O(RTT) | 100ms par notification |
| Charge DB | O(1) requête | 1 requête / notification |
| Mémoire app | O(1) | rien en RAM |

**Problème :** 100ms de latence fixe. Avec 100 notifications/seconde → 100 requêtes/seconde en DB.

---

### Approche 2 : Scan de tous les utilisateurs connectés (brute force)

```
Pour chaque notification reçue :
    Pour chaque userId dans userSinks (U_total utilisateurs) :
        Si user.role == roleCible ET user.etab == etablissementId :
            push SSE
```

| Métrique | Complexité | Valeur exemple (1000 users) |
|---|---|---|
| Temps CPU | O(U_total) | 1000 comparaisons |
| Latence réseau | O(0) | 0ms (tout en RAM) |
| Charge DB | O(0) | 0 requête |
| Mémoire app | O(U_total) | proportionnel aux users connectés |

**Problème :** Avec 10 000 utilisateurs connectés → 10 000 comparaisons par notification.
Acceptable en dev, problématique à grande échelle.

---

### Approche 3 : Inverted Index en mémoire (implémentée)

```
Pour chaque notification reçue :
    byRole = groupSinks["role:PHARMACIEN"]     → O(1) lookup HashMap
    byEtab = groupSinks["etab:hopital-A"]      → O(1) lookup HashMap
    targets = intersection(byRole, byEtab)     → O(min(|byRole|, |byEtab|)) = O(n)
    Pour chaque userId dans targets : push SSE → O(n)
```

| Métrique | Complexité | Valeur exemple |
|---|---|---|
| Temps CPU | O(p) lookup + O(n) intersection | O(2) + O(50) = ~52 ops |
| Latence réseau | O(0) | 0ms |
| Charge DB | O(0) | 0 requête |
| Mémoire app | O(p × U_total) | 2 × 1000 = 2000 entrées |

**p × U_total expliqué :** chaque utilisateur apparaît dans p index différents (un par critère).
Avec 2 critères et 1000 users : 2000 entrées en mémoire. Très acceptable.

---

### Tableau comparatif final

```
                    Approche 1          Approche 2          Approche 3
                    (Requête DB)        (Scan complet)      (Inverted Index) ✓

Temps push          O(RTT) = 100ms      O(U_total)          O(p×n) ≈ microsecondes
Charge DB           1 req/notif         0                   0
Mémoire             O(1)                O(U_total)          O(p × U_total)
Scalabilité         Limitée par DB      Limitée par CPU     Haute
Complexité code     Simple              Simple              Modérée
Latence réelle      ~100ms              ~1ms (10K users)    <0.1ms
```

**Choix retenu : Approche 3** — meilleur ratio performance/scalabilité pour du temps réel SSE.

---

## 4. Arbre d'expressions booléennes AND/OR/NOT

### Le problème du ciblage multi-critères dynamiques

L'implémentation actuelle supporte :
```
roleCible = "PHARMACIEN"  ET  etablissementId = "hopital-A"
```
C'est du AND fixe sur 2 critères fixes. Mais en réalité, les besoins métier sont :

```
"Tous les pharmaciens de l'hôpital A en garde de nuit"
→ role=PHARMACIEN AND etab=hopital-A AND groupe=garde-nuit

"Tous les médecins OU les infirmiers de l'hôpital B"
→ (role=MEDECIN OR role=INFIRMIER) AND etab=hopital-B

"Tous sauf les stagiaires"
→ role=PHARMACIEN AND NOT groupe=stagiaire
```

**Problème clé :** Les critères ne sont pas connus à l'avance. Ils varient selon le module
émetteur (pharmacie n'a pas les mêmes critères que le labo).

### La solution : Boolean Expression Tree (arbre d'expressions)

Inspiré de la syntaxe `bool query` d'Elasticsearch :

```json
{
  "bool": {
    "must": [
      { "term": { "role": "PHARMACIEN" } },
      { "term": { "etab": "hopital-A" } }
    ],
    "should": [
      { "term": { "groupe": "garde-nuit" } }
    ],
    "must_not": [
      { "term": { "groupe": "stagiaire" } }
    ]
  }
}
```

### Structure de données — CriteriaNode récursif

```java
public abstract class CriteriaNode {
    public abstract Set<String> resolve(Map<String, CopyOnWriteArraySet<String>> index);
}

// Noeud feuille : un critère simple (role=PHARMACIEN)
public class TermNode extends CriteriaNode {
    private final String key;   // ex: "role"
    private final String value; // ex: "PHARMACIEN"

    @Override
    public Set<String> resolve(Map<String, CopyOnWriteArraySet<String>> index) {
        Set<String> result = index.get(key + ":" + value);
        return result != null ? new HashSet<>(result) : Collections.emptySet();
    }
}

// Noeud AND : intersection de tous les enfants
public class AndNode extends CriteriaNode {
    private final List<CriteriaNode> children;

    @Override
    public Set<String> resolve(Map<String, CopyOnWriteArraySet<String>> index) {
        return children.stream()
            .map(child -> child.resolve(index))
            .reduce((a, b) -> { a.retainAll(b); return a; })
            .orElse(Collections.emptySet());
    }
}

// Noeud OR : union de tous les enfants
public class OrNode extends CriteriaNode {
    private final List<CriteriaNode> children;

    @Override
    public Set<String> resolve(Map<String, CopyOnWriteArraySet<String>> index) {
        return children.stream()
            .map(child -> child.resolve(index))
            .reduce((a, b) -> { a.addAll(b); return a; })
            .orElse(Collections.emptySet());
    }
}

// Noeud NOT : complément (tous les connectés SAUF ces users)
public class NotNode extends CriteriaNode {
    private final CriteriaNode child;

    @Override
    public Set<String> resolve(Map<String, CopyOnWriteArraySet<String>> index) {
        Set<String> excluded = child.resolve(index);
        Set<String> allConnected = new HashSet<>(/* userSinks.keySet() */);
        allConnected.removeAll(excluded);
        return allConnected;
    }
}
```

### Exemple d'utilisation

```java
// "Tous les pharmaciens de hopital-A en garde de nuit, sauf les stagiaires"
CriteriaNode criteria = new AndNode(List.of(
    new TermNode("role", "PHARMACIEN"),
    new TermNode("etab", "hopital-A"),
    new TermNode("groupe", "garde-nuit"),
    new NotNode(new TermNode("groupe", "stagiaire"))
));

Set<String> targets = criteria.resolve(groupSinks);
```

### Modification du GenericNotificationEvent

Pour supporter les critères dynamiques, l'événement doit transporter l'arbre de critères :

```java
public class GenericNotificationEvent {
    // Champs existants
    private String moduleEmetteur;
    private String typeAlerte;
    private String niveauGravite;
    private String titre;
    private String message;
    private String etablissementId;
    private Map<String, Object> donneesMetier;

    // Nouveau : critères de ciblage dynamiques
    private List<String> rolesCibles;          // plusieurs rôles possibles
    private List<String> etablissementsCibles; // plusieurs établissements
    private List<String> groupesCibles;        // garde, service, équipe...
    private List<String> destinatairesSpecifiques; // userIds directs
    private String operateur;                  // "AND" | "OR" (défaut: AND)
}
```

### Sérialisation JSON pour RabbitMQ

```json
{
  "moduleEmetteur": "PHARMACIE",
  "typeAlerte": "RUPTURE_STOCK",
  "niveauGravite": "CRITIQUE",
  "titre": "Rupture Amoxicilline 500mg",
  "message": "Stock à 0. Action requise.",
  "rolesCibles": ["PHARMACIEN", "MEDECIN_CHEF"],
  "etablissementsCibles": ["hopital-A"],
  "groupesCibles": ["garde-nuit"],
  "operateur": "AND",
  "donneesMetier": {
    "medicamentId": "AMX-500",
    "stockActuel": "0"
  }
}
```

### Construction dynamique de l'arbre depuis l'événement

```java
public CriteriaNode buildCriteria(GenericNotificationEvent event) {
    List<CriteriaNode> nodes = new ArrayList<>();

    if (event.getRolesCibles() != null && !event.getRolesCibles().isEmpty()) {
        // Plusieurs rôles → OR entre eux
        nodes.add(new OrNode(
            event.getRolesCibles().stream()
                .map(r -> new TermNode("role", r))
                .collect(Collectors.toList())
        ));
    }

    if (event.getEtablissementsCibles() != null) {
        nodes.add(new OrNode(
            event.getEtablissementsCibles().stream()
                .map(e -> new TermNode("etab", e))
                .collect(Collectors.toList())
        ));
    }

    if (event.getGroupesCibles() != null) {
        nodes.add(new OrNode(
            event.getGroupesCibles().stream()
                .map(g -> new TermNode("groupe", g))
                .collect(Collectors.toList())
        ));
    }

    // Opérateur global (AND par défaut)
    return nodes.size() == 1 ? nodes.get(0) : new AndNode(nodes);
}
```

### Complexité de l'arbre d'expressions

```
AndNode(p critères)  → O(p) lookups HashMap + O(n) intersections
OrNode(k critères)   → O(k) lookups HashMap + O(k×n) unions
NotNode              → O(U_total) - O(n_exclu)

Cas typique (2 AND + 1 OR de 2 rôles) :
    O(2) + O(n) + O(2) + O(2×n) ≈ O(3n)    — toujours sub-linéaire en U_total
```

---

## 5. Cassandra — Modèle de données, limitations, redesign

### Différence fondamentale avec SQL

En SQL, la PRIMARY KEY identifie uniquement une ligne. En Cassandra, elle définit
**comment les données sont physiquement distribuées et ordonnées** sur le cluster.

```sql
-- SQL : PRIMARY KEY = identifiant unique
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP,
    role VARCHAR(50)
);
-- On peut faire : ORDER BY created_at DESC   ← possible car index B-tree

-- Cassandra : PRIMARY KEY = distribution + ordre physique
CREATE TABLE notifications (
    id UUID,
    created_at TIMESTAMP,
    role TEXT,
    PRIMARY KEY (id)   -- partition key = id, pas de clustering key
);
-- ORDER BY created_at DESC → ERREUR : created_at n'est pas clustering key
```

### Partition Key vs Clustering Key

```
PRIMARY KEY ((partition_key), clustering_key_1, clustering_key_2, ...)
                │                       │
                │                       └── Ordre physique des lignes DANS la partition
                └── Détermine sur quel(s) nœud(s) du cluster la donnée est stockée
                    (hachage cohérent → distribution automatique)
```

**Règle d'or Cassandra :** une requête efficace doit spécifier la partition key en entier (WHERE partition = X).
Sans ça → ALLOW FILTERING = scan de TOUS les nœuds = latence élevée.

### Schéma actuel et ses limitations

```sql
-- Schéma actuel (simplifié)
CREATE TABLE notifications (
    id UUID PRIMARY KEY,   -- partition key = id
    created_at TIMESTAMP,
    role_cible TEXT,
    etablissement_id TEXT,
    ...
);
```

**Limitations :**
1. `findAll()` ne peut pas ORDER BY `created_at` → Cassandra ne peut pas trier sans clustering key
2. `findByRoleCible()` nécessite `ALLOW FILTERING` → scatter-gather sur tous les nœuds
3. `countByLueFalse()` nécessite `ALLOW FILTERING` → même problème

### Redesign recommandé — Bucket + Clustering Key

Le problème du tri vient du fait que `created_at` n'est pas une clustering key.
Solution : utiliser un **bucket temporel** comme partition key + `created_at` comme clustering key.

```sql
CREATE TABLE notifications (
    bucket      TEXT,        -- ex: "2025-05" (année-mois)
    created_at  TIMESTAMP,   -- clustering key → tri natif
    id          UUID,        -- pour l'unicité
    role_cible  TEXT,
    etablissement_id TEXT,
    titre       TEXT,
    message     TEXT,
    lue         BOOLEAN,
    -- ... autres champs ...

    PRIMARY KEY ((bucket), created_at, id)
) WITH CLUSTERING ORDER BY (created_at DESC)
  AND default_time_to_live = 7776000;  -- TTL 90 jours
```

**Avantages :**
- `ORDER BY created_at DESC` natif → zéro tri en mémoire
- Les notifications d'un même mois sont co-localisées → lecture rapide
- TTL 90 jours = environ 3 buckets actifs max (mois courant + 2 précédents)

**Requêtes efficaces avec ce schéma :**

```java
// Dernières notifications du mois courant
SELECT * FROM notifications WHERE bucket = '2025-05'
    ORDER BY created_at DESC LIMIT 50;

// Recherche sur 3 mois
SELECT * FROM notifications WHERE bucket IN ('2025-03', '2025-04', '2025-05')
    ORDER BY created_at DESC;
```

**Code Java — calcul du bucket :**

```java
public String getBucket(Instant createdAt) {
    return DateTimeFormatter.ofPattern("yyyy-MM")
        .withZone(ZoneId.of("UTC"))
        .format(createdAt);
    // Retourne ex: "2025-05"
}
```

### Entité Cassandra mise à jour

```java
@Table("notifications")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationDocument {

    @PrimaryKeyColumn(name = "bucket", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String bucket;

    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    private UUID id;

    @Column private String titre;
    @Column private String message;
    @Column("role_cible")        private String roleCible;
    @Column("etablissement_id")  private String etablissementId;
    @Column private Boolean lue;
    // ... autres champs ...
}
```

### Indexation Cassandra — ce qu'il faut éviter

L'indexation secondaire Cassandra (`@Indexed`) déclenche un **scatter-gather** :
la requête est envoyée à TOUS les nœuds du cluster, chaque nœud répond avec ses résultats,
et le coordinateur agrège le tout.

```
Client → Coordinateur → Nœud 1, Nœud 2, Nœud 3, Nœud 4...  (scatter)
                      ← résultats de chaque nœud              (gather)
                      → agrégation + réponse au client
```

**Avec 10 nœuds et 1M de notifications :** 10 lectures parallèles de 100K chaque.
C'est tolérable pour des requêtes rares mais catastrophique pour des requêtes fréquentes.

**Solution :** l'inverted index en mémoire évite ces requêtes d'indexation Cassandra
pour le ciblage en temps réel. Cassandra est utilisé uniquement pour la persistance (write)
et la récupération de l'historique (read par bucket).

### Comparaison MongoDB vs Cassandra pour ce projet

| Critère | MongoDB | Cassandra |
|---|---|---|
| TTL natif | `@Indexed(expireAfterSeconds)` | `default_time_to_live` au niveau table |
| Schéma flexible | Oui (BSON) | Non (schema fixe) |
| Tri | Facile (index B-tree sur tout champ) | Uniquement sur clustering key |
| Scalabilité écriture | Bonne (replica sets) | Excellente (distributed, no master) |
| ALLOW FILTERING | N/A (MongoDB indexe librement) | À éviter (coûteux) |
| Raison du choix | Solution initiale, très adaptée | Alignement stack équipe |

**Conclusion :** MongoDB était techniquement adapté. La migration vers Cassandra a été
motivée par l'alignement avec les standards de l'équipe, pas par une limite de MongoDB.

---

## 6. Synthèse et roadmap d'évolution

### État actuel (v1.0 — implémenté)

```
✅ Consommation RabbitMQ multi-queues (pharmacie, urgences, labo, critique)
✅ Dead Letter Queue pour les messages en erreur
✅ Persistance Cassandra (Astra DB) avec TTL 90 jours
✅ Inverted Index en mémoire (role + établissement)
✅ Push SSE temps réel < 50ms
✅ Support multi-onglets par utilisateur
✅ API REST : liste, compteur non lus, acquittement
✅ Heartbeat SSE toutes les 25 secondes
✅ Documentation architecture (README)
```

### Roadmap d'évolution (par ordre de priorité)

#### v1.1 — Ciblage multi-critères (Inverted Index étendu)

**Effort :** 2-3 jours  
**Impact :** Haute valeur métier (ciblage précis)

- Étendre `GenericNotificationEvent` avec `List<String>` pour chaque critère
- Implémenter `CriteriaNode` (TermNode, AndNode, OrNode, NotNode)
- Enrichir l'inverted index avec les groupes, services, équipes
- Zéro breaking change sur l'API existante

#### v1.2 — Authentification JWT sur l'endpoint SSE

**Effort :** 1-2 jours  
**Impact :** Sécurité critique avant mise en production

- Filtre Spring Security sur `/api/notifications/stream`
- Extraction du `userId` et du `role` depuis le token JWT
- Suppression des paramètres URL `userId` et `role` (vecteur d'usurpation)

```java
// Actuel (non sécurisé)
GET /stream?userId=user-123&role=PHARMACIEN

// Cible (sécurisé)
GET /stream  +  Authorization: Bearer <jwt>
```

#### v1.3 — Pagination de l'historique

**Effort :** 1 jour  
**Impact :** Performance (évite de charger 10K notifications d'un coup)

```java
GET /api/notifications?page=0&size=20&bucket=2025-05
```

#### v1.4 — Redesign schéma Cassandra (bucket + clustering key)

**Effort :** 2-3 jours (migration de données)  
**Impact :** Tri natif, performances lectures

- Nouvelle table avec `(bucket, created_at DESC, id)` comme PRIMARY KEY
- Script de migration des données existantes
- Suppression de `ALLOW FILTERING` sur les requêtes de liste

#### v2.0 — Support multi-instances (Redis Pub/Sub)

**Effort :** 3-5 jours  
**Impact :** Scalabilité horizontale

- Redis Pub/Sub comme canal de coordination inter-instances
- Chaque instance publie sur Redis après réception RabbitMQ
- Chaque instance subscribe et push vers ses sinks SSE locaux
- Cassandra garantit la persistance même si Redis rate un message

### Architecture cible v2.0

```
Modules métier
    │
    ▼
RabbitMQ (Topic Exchange sih.events)
    │
    ▼
CentralNotificationConsumer (1 instance)
    ├── Persistance Cassandra (une seule fois)
    └── Redis PUBLISH "channel:notifications"
                │
    ┌───────────┼───────────┐
    ▼           ▼           ▼
Instance 1  Instance 2  Instance 3
(subscriber) (subscriber) (subscriber)
    │           │           │
SSE clients SSE clients SSE clients
```

---

*Document généré dans le cadre du stage PFE — Hicham Azeroual — Enova*  
*Dernière mise à jour : Mai 2025*