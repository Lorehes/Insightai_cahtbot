-- V2__add_messages_and_conversations_indexes.sql
-- 조회 인덱스 2개 추가: messages(conversation_id, role), conversations(user_id, updated_at DESC)
-- WHERE deleted_at IS NULL. 사용자 승인(G5, 2026-08-27) 완료.
--
-- 근거(실측, 라이브 chatbot-db 컨테이너 직접 접속):
--   \d messages       -> 인덱스는 messages_pkey(PK, id) 하나뿐
--   \d conversations   -> 인덱스는 conversations_pkey(PK, id) 하나뿐 (FK user_id 에도 없음)
--   EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM messages
--     WHERE conversation_id = '...' AND role = 'user';        -> Seq Scan on messages
--   합성 데이터(5만행)로 재현한 conversations 목록 조회        -> Seq Scan + Sort, 14.8ms
-- 리뷰 근거: chat-T9-round2 review Medium-2(messages), chat-T8 review 1R Low-6(conversations).
--
-- ParadeDB "테이블당 인덱스 1개" 제약(프로젝트 CLAUDE.md 사실 #8) 재확인 — 걸림돌 아님:
-- 그 제약은 V1__init.sql:104-106 주석대로 `USING paradedb` BM25 인덱스 전용이다. 아래 두 인덱스는
-- 둘 다 기본 btree 접근 방식(USING 절 생략 시 기본값)이고, messages/conversations 는 애초에
-- ParadeDB 인덱스를 하나도 갖지 않는 테이블이다(BM25 인덱스는 vector_store_bgem3 전용). 위 \d
-- 결과가 그 사실 자체도 다시 확인해준다 — 두 테이블 모두 PK 외 인덱스가 없었다.
--
-- CREATE INDEX CONCURRENTLY 를 쓰지 않는 이유: Flyway 는 PostgreSQL 마이그레이션을 기본적으로
-- 트랜잭션으로 감싸는데, CONCURRENTLY 는 트랜잭션 블록 안에서 실행할 수 없다(별도 전용 설정 없이는
-- 이 프로젝트 Flyway 구성에 없음). 두 테이블 모두 이 시점 기준 0행이라 블로킹 빌드 비용이 사실상
-- 0이므로 V1 의 기존 인덱스들과 동일하게 평범한 CREATE INDEX 로 충분하다.

-- messages(conversation_id, role): ConversationService#enforceMessageQuota(T9)가 메시지 전송마다
-- `WHERE conversation_id = ? AND role = 'user'`로 카운트한다(app.session.max-messages-per-conversation
-- 상한 검사). conversation_id 단독이 아니라 role 까지 포함한 복합 인덱스로 만든 이유: 이 카운트
-- 쿼리는 항상 두 조건을 함께 쓰고, role 은 값이 2종뿐이라 별도 인덱스를 만들 이유가 없다 — 복합
-- 인덱스 하나가 두 조건 전부를 인덱스 단계에서 해소해, 힙에서 추가로 role 을 걸러낼 필요가 없다.
-- conversation_id 가 선두 컬럼이라 findByConversationIdOrderByCreatedAtAsc(메시지 이력 조회,
-- WHERE conversation_id = ?)의 동등 조건도 부가적으로 이 인덱스를 탄다(정렬 대상인 created_at 은
-- 인덱스에 없어 그 쪽 ORDER BY 까지 커버하지는 못한다 — 이번 카드 범위 밖).
CREATE INDEX idx_messages_conversation_id_role
    ON messages (conversation_id, role);

-- conversations(user_id, updated_at DESC) WHERE deleted_at IS NULL: 목록 조회
-- (ConversationRepository#findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc, GET /api/conversations)가
-- `WHERE user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC`를 쓴다.
--
-- 부분 인덱스(WHERE deleted_at IS NULL)로 만든 이유: chat:AC-16 이 소프트 삭제 행의 물리적 보존을
-- 요구해 deleted_at IS NOT NULL 행은 삭제되지 않고 계속 쌓이기만 한다(ConversationService#
-- deleteConversation [Caution] 참고 — 삭제해도 quota 만 돌아올 뿐 행은 영구 보존됨). 이 쿼리는
-- deleted_at IS NULL 행만 대상으로 하므로, 부분 인덱스는 크기가 "생존 대화 수"에만 비례하고 소프트
-- 삭제 누적과 무관하게 유지된다 — 전체 인덱스였다면 죽은 행 비율이 시간이 지날수록 계속 늘어난다.
--
-- 컬럼 순서(user_id 선두, updated_at DESC 후행)로 만든 이유: WHERE 절의 등치 조건(user_id)과
-- ORDER BY 절(updated_at DESC)을 인덱스 하나로 동시에 충족시켜, 정렬을 위한 별도 Sort 단계를
-- 없앤다. 실측: 인덱스 도입 전에는 합성 5만행 기준 Seq Scan + Sort, Execution Time 14.8ms
-- (T8 1R Low-6). 부가 효과: 같은 컬럼 접두(user_id, deleted_at IS NULL)를 쓰는
-- countByUserIdAndDeletedAtIsNull(createConversation 의 대화 상한 검사)도 이 인덱스를 탄다.
CREATE INDEX idx_conversations_user_id_updated_at
    ON conversations (user_id, updated_at DESC)
    WHERE deleted_at IS NULL;
