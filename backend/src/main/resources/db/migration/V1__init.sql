-- V1__init.sql
-- Initial schema: users, conversations, messages, documents, vector_store_bgem3.
-- Source of truth for the data model is chatbot-plan-v0.4.md §5 (:183-204),
-- as carried into docs/specs/2026-08-25_chat.md §5 and Tech Review A-1/A-13/D-8.

-- ── Extensions ──
-- Intentionally NOT created here. CREATE EXTENSION requires superuser, and this
-- migration runs on the same datasource as the app (Flyway shares spring.datasource).
-- Keeping it here would force the runtime account to be a Postgres superuser just to
-- apply V1, which also brings rolbypassrls along and makes DB-level access control
-- (e.g. RLS) impossible in principle (review 2026-08-25 Block High-1, security).
-- vector/pg_search are created once by infra/init-db/01-bootstrap-extensions-and-app-role.sh,
-- which runs as the real bootstrap superuser via docker-entrypoint-initdb.d on first
-- container start; that same script also creates the non-superuser app role (APP_DB_USER
-- in infra/.env) that Flyway/the app connect as (see application.yaml datasource).
-- NOTE: do not write "$" + "{...}" placeholder syntax in this file's comments — Flyway's
-- default placeholder replacement scans comments too and fails the build if unresolved
-- (verified: broke this exact migration during the High-1 rework, review 2026-08-25).

-- ── users ──
-- id uses gen_random_uuid() (built into PostgreSQL core since v13, no extension
-- needed), not uuid_generate_v4() which would require uuid-ossp (Tech Review A-1).
CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_provider varchar(32)  NOT NULL,
    external_id   varchar(255),
    display_name  varchar(255),
    created_at    timestamp with time zone NOT NULL DEFAULT now()
);

-- ── conversations ──
-- id is uuid, not bigserial: AC-17 isolation relies in part on ids being hard to
-- guess (Tech Review A-1). deleted_at is D-7/D-8: soft delete, absent from the
-- original §5 model, added per OQ-13 resolution (chat Spec §7).
CREATE TABLE conversations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users (id),
    title       varchar(255),
    model       varchar(64) NOT NULL,
    rag_enabled boolean NOT NULL DEFAULT false,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    deleted_at  timestamp with time zone
);

-- ── messages ──
-- id is bigserial: never exposed externally, insertion order doubles as
-- conversation order (Tech Review A-1). finish_reason is D-8 (gate-2 decision):
-- absent from the original §5 model, added to persist OQ-6's "cancelled" state.
-- cost_usd is numeric (not real/double precision/float) per project rule on
-- monetary columns.
CREATE TABLE messages (
    id                 bigserial PRIMARY KEY,
    conversation_id    uuid NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role               varchar(16) NOT NULL,
    content            text NOT NULL,
    model              varchar(64),
    prompt_tokens      integer,
    completion_tokens  integer,
    cost_usd           numeric,
    sources            jsonb,
    finish_reason      varchar(32),
    created_at         timestamp with time zone NOT NULL DEFAULT now()
);

-- ── documents ──
-- DDL only in this Spec. Functional use (insert/search) belongs to the
-- document-ingestion Spec.
CREATE TABLE documents (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    filename      varchar(255) NOT NULL,
    title         varchar(255),
    mime_type     varchar(128),
    size_bytes    bigint,
    status        varchar(32) NOT NULL,
    error_message text,
    chunk_count   integer,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    indexed_at    timestamp with time zone
);

-- ── vector_store_bgem3 ──
-- Column names/types match the schema PgVectorStore itself would create
-- (org.springframework.ai.vectorstore.pgvector.PgVectorStore#initializeSchema,
-- Spring AI 2.0.1 sources, Tech Review A-13-①): id uuid PK, content text,
-- metadata json (not jsonb), embedding vector(n). Flyway owns this schema;
-- spring.ai.vectorstore.pgvector.initialize-schema stays false (chat Spec §5.1).
CREATE TABLE vector_store_bgem3 (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    embedding vector(1024)
);

-- ── indexes (exactly 2, both on vector_store_bgem3 — card T1 scope) ──

-- HNSW vector index for cosine similarity search (chat Spec §5, §5.2).
CREATE INDEX idx_vector_store_bgem3_embedding
    ON vector_store_bgem3 USING hnsw (embedding vector_cosine_ops);

-- ParadeDB BM25 index for Korean full-text search. Syntax verified by running
-- this exact DDL against the live paradedb/paradedb:0.25.4-pg18 container
-- (Tech Review A-13-②) — not copied from a web example (project rule P3).
-- ParadeDB allows only one such index per table (chat Spec §5.2), so this is
-- the only paradedb-method index here; it coexists with the hnsw index above
-- because hnsw is a plain pgvector access method, not a ParadeDB index.
CREATE INDEX idx_vector_store_bgem3_bm25
    ON vector_store_bgem3
    USING paradedb (id, (content::pdb.lindera(korean)))
    WITH (key_field = 'id');
