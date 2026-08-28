-- V2__add_messages_and_conversations_indexes_rollback.sql
-- V2__add_messages_and_conversations_indexes.sql 의 수동 롤백. V1__init_rollback.sql 과 동일한
-- 이유로 Flyway 기본 마이그레이션 위치(db/migration)가 아닌 db/rollback 에 둔다 — 자동 적용되지
-- 않는다. 사용자 승인 후, 대상 데이터베이스에 대해 파일 전체를 처음부터 끝까지 수동 실행한다.

DROP INDEX IF EXISTS idx_conversations_user_id_updated_at;
DROP INDEX IF EXISTS idx_messages_conversation_id_role;

-- V1__init_rollback.sql 과 동일한 이유(그 파일 하단 주석 참고)로 flyway_schema_history 에서도
-- 이 버전 행을 지운다. 지우지 않으면 인덱스는 사라졌는데 Flyway 는 V2 가 이미 적용된 것으로
-- 믿어 다음 `flyway migrate` 가 아무 것도 하지 않는다 — DROP 만 실행하고 이 DELETE 를 생략하지
-- 말 것. 파일 전체를 실행하거나, 전혀 실행하지 않거나 둘 중 하나여야 한다.
DELETE FROM flyway_schema_history WHERE version = '2';
