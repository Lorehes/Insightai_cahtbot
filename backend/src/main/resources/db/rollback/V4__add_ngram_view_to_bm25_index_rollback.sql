-- V4__add_ngram_view_to_bm25_index_rollback.sql
-- V4__add_ngram_view_to_bm25_index.sql 의 수동 롤백. V1__init_rollback.sql 과 동일한 이유로
-- Flyway 기본 마이그레이션 위치(db/migration)가 아닌 db/rollback 에 둔다 — 자동 적용되지 않는다.
-- 사용자 승인 후, 대상 데이터베이스에 대해 파일 전체를 처음부터 끝까지 수동 실행한다.
--
-- 인덱스를 V1 정의(형태소 뷰 1개)로 되돌린다. 롤백 후에는 ParadeDbNgramSearchAdapter 의
-- `content::pdb.alias('content_ngram')` 질의가 실패하므로 app.ngram.enabled=false 로 함께 내린다.

DROP INDEX IF EXISTS idx_vector_store_bgem3_bm25;

CREATE INDEX idx_vector_store_bgem3_bm25
    ON vector_store_bgem3
    USING paradedb (id, (content::pdb.lindera(korean)))
    WITH (key_field = 'id');

-- V1__init_rollback.sql 과 동일한 이유(그 파일 하단 주석 참고)로 flyway_schema_history 에서도
-- 이 버전 행을 지운다. 파일 전체를 실행하거나, 전혀 실행하지 않거나 둘 중 하나여야 한다.
DELETE FROM flyway_schema_history WHERE version = '4';
