-- V1__init_rollback.sql
-- Manual rollback for V1__init.sql. Not in Flyway's default migration location
-- (db/migration), so it is never auto-applied (Tech Review A-13-④). Run by hand,
-- with user approval, against the target database.
--
-- Order is the reverse of creation: drop indexes/tables that depend on others first.
--
-- IMPORTANT: this script is meant to be executed manually, end to end, including
-- the flyway_schema_history DELETE at the bottom. If you run only the DROPs and
-- skip the DELETE, Flyway still believes V1 is applied; a later `flyway migrate`
-- will see "already up to date" and create nothing, while the app (ddl-auto:
-- validate) fails to start against the now-empty schema. Run the whole file, or
-- not at all.

DROP INDEX IF EXISTS idx_vector_store_bgem3_bm25;
DROP INDEX IF EXISTS idx_vector_store_bgem3_embedding;

DROP TABLE IF EXISTS vector_store_bgem3;
DROP TABLE IF EXISTS documents;
DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS conversations;
DROP TABLE IF EXISTS users;

-- Extensions are intentionally NOT dropped: paradedb/paradedb:0.25.4-pg18 preloads
-- vector and pg_search at the image level, and dropping them could affect other
-- objects/extensions in the same database (e.g. postgis depends on shared catalog
-- state). Extension lifecycle is an infra concern, not this migration's.

-- Reviewed 2026-08-25 (rework, Medium-2 correctness): dropping the objects above is
-- not the true inverse of V1__init.sql on its own. Flyway tracks applied versions in
-- flyway_schema_history; if that row for version '1' survives, Flyway treats the
-- schema as already migrated even though the tables/indexes are gone. Re-running
-- `flyway migrate` then does nothing, and the app fails fast on ddl-auto: validate
-- because the expected tables don't exist. Deleting the history row here makes this
-- rollback script a real inverse of the migration, so a subsequent migrate recreates
-- everything from V1__init.sql as if it had never run.
DELETE FROM flyway_schema_history WHERE version = '1';
