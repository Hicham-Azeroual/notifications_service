-- =====================================================
-- schema.cql - Cassandra DDL pour le service notifications
-- À exécuter manuellement en production (schema-action: NONE)
-- En dev : Spring le crée automatiquement (schema-action: CREATE_IF_NOT_EXISTS)
-- =====================================================

-- Keyspace : SimpleStrategy pour dev/test, NetworkTopologyStrategy en prod
CREATE KEYSPACE IF NOT EXISTS sih_notifications
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
  AND durable_writes = true;

USE sih_notifications;

-- Table principale
-- Partition key : id (UUID) — une partition par notification
-- Index secondaires sur etablissement_id et role_cible pour les requêtes filtrées
CREATE TABLE IF NOT EXISTS notifications (
    id               UUID PRIMARY KEY,
    type             TEXT,
    type_alerte      TEXT,
    titre            TEXT,
    message          TEXT,
    module           TEXT,
    etablissement_id TEXT,
    role_cible       TEXT,
    destinataires    LIST<TEXT>,
    lue              BOOLEAN,
    acquittee        BOOLEAN,
    resolue          BOOLEAN,
    metadata         MAP<TEXT, TEXT>,
    created_at       TIMESTAMP,
    lue_at           TIMESTAMP,
    acquittee_at     TIMESTAMP,
    acquitte_par     TEXT
);

-- Index secondaires pour les requêtes findByEtablissementId + findByRoleCible
CREATE INDEX IF NOT EXISTS idx_notif_etablissement ON notifications (etablissement_id);
CREATE INDEX IF NOT EXISTS idx_notif_role          ON notifications (role_cible);
CREATE INDEX IF NOT EXISTS idx_notif_lue           ON notifications (lue);

-- =====================================================
-- NOTE PROD : remplacer SimpleStrategy par NetworkTopologyStrategy
-- CREATE KEYSPACE IF NOT EXISTS sih_notifications
--   WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3};
-- =====================================================
