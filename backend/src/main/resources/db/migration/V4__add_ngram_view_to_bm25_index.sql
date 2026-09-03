-- V4__add_ngram_view_to_bm25_index.sql
-- 다국어 총람 §4-3 / 사용자 사전 실측 보고서 §4·§5 B안 (2026-09-03, 사용자 승인 「5번은 B랑 묶어서 진행」).
--
-- 왜 필요한가:
--   pg_search 0.25.4 의 lindera(korean) 에는 사용자 사전이 없어(실측 확정) 사전에 없는 고유명사·
--   제품코드·오타를 형태소 축이 놓친다. 같은 컬럼을 문자 2~3-gram 으로도 색인해 두 번째
--   키워드 뷰(alias=content_ngram)를 만들면, 글자 조각 일치로 그런 문자열을 잡는다
--   (임시 테이블 실측: '인사이트에이아이' n-gram 전체일치 → 정답 1건만, 오타 '인사이트에이아리'
--   에도 정답 1위, 'ES-2024-A' → 정답 1건만).
--
-- 왜 인덱스를 다시 만드는가:
--   ParadeDB 는 테이블당 paradedb 인덱스 1개만 허용한다(프로젝트 규약 사실 #8). 뷰를 더하려면
--   기존 인덱스를 지우고 표현식 2개짜리로 다시 만들어야 한다. 22행 규모에서 초 단위이며
--   임베딩(hnsw 인덱스·embedding 컬럼)은 건드리지 않는다.
--
-- 문법 근거: 공식 문서 docs/documentation/tokenizers/multiple-per-field.mdx 의 alias 문법을
--   0.25.4 컨테이너 임시 테이블에서 실행해 확인(alias 는 토크나이저 옵션 문자열 'alias=<name>').
--   pdb.ngram(2,3) 의 세 번째 인자(prefix_only)는 생략 = false.
--
-- 질의 측: 형태소 뷰는 기존과 같이 `content ||| ?`, n-gram 뷰는
--   `content::pdb.alias('content_ngram') ||| ?` (ParadeDbNgramSearchAdapter).
--
-- 롤백: db/rollback/V4__add_ngram_view_to_bm25_index_rollback.sql (수동).

DROP INDEX IF EXISTS idx_vector_store_bgem3_bm25;

CREATE INDEX idx_vector_store_bgem3_bm25
    ON vector_store_bgem3
    USING paradedb (
        id,
        (content::pdb.lindera(korean)),
        (content::pdb.ngram(2, 3, 'alias=content_ngram'))
    )
    WITH (key_field = 'id');
