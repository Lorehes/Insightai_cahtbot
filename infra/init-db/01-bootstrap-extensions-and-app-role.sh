#!/usr/bin/env bash
# 부트스트랩 전용 스크립트. docker-entrypoint-initdb.d 관례에 따라 컨테이너가
# "빈" 데이터 디렉터리로 최초 기동될 때 postgres 공식 이미지 엔트리포인트가
# POSTGRES_USER(슈퍼유저)로 1회만 실행한다. 이미 데이터가 있는 볼륨을 재기동해도
# 다시 돌지 않는다 — 볼륨을 지우고(docker compose down -v) 재기동해야 적용된다.
#
# 목적: review 2026-08-25 Block High-1(security) 대응.
#   - CREATE EXTENSION 은 슈퍼유저 권한이 필요하다. 이 두 줄을 V1__init.sql(Flyway,
#     앱과 동일 계정)에 남겨두면 앱 런타임 계정 자체가 슈퍼유저여야 하는 구조가 된다.
#   - 그래서 확장 생성은 여기(부트스트랩, 진짜 슈퍼유저)로 옮기고, 앱·Flyway 는
#     비슈퍼유저 전용 역할(${APP_DB_USER})로 접속하게 분리한다.
#
# .sh 확장자를 쓰는 이유: 공식 postgres 이미지는 docker-entrypoint-initdb.d 안의
# *.sh 파일만 셸로 소싱해 컨테이너 환경변수($APP_DB_USER/$APP_DB_PASSWORD, compose의
# environment: 로 주입됨)를 그대로 참조할 수 있다. *.sql 파일은 psql -f 로만 실행되고
# 별도 치환(envsubst 등) 없이 그대로 서버에 전달되므로 비밀번호를 변수로 넣을 수 없다.
set -euo pipefail

: "${APP_DB_USER:?APP_DB_USER must be set (infra/.env)}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD must be set (infra/.env)}"

# psql -v 로 셸 변수를 psql 변수로 넘긴다. heredoc 안에서 ${APP_DB_PASSWORD} 처럼 셸이
# 직접 SQL 문자열에 보간하면, 값에 작은따옴표가 섞였을 때 SQL 이 깨지거나(초기화 실패)
# 슈퍼유저 세션에서 임의 SQL 이 실행될 여지가 생긴다(review 2026-08-25 Medium-2).
# 아래 SQL 안에서는 :'app_pw'(리터럴 안전 인용) / :"app_user"(식별자 안전 인용) 같은
# psql 변수 치환 문법만 쓴다 — 셸 보간을 전혀 거치지 않는다.
psql -v ON_ERROR_STOP=1 \
    -v app_user="$APP_DB_USER" \
    -v app_pw="$APP_DB_PASSWORD" \
    -v db_name="$POSTGRES_DB" \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
    -- pgvector, ParadeDB(pg_search) 확장. CREATE EXTENSION 은 슈퍼유저 권한이 필요해
    -- 여기(부트스트랩)에서만 실행한다 (V1__init.sql 에서 이관, review High-1).
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE EXTENSION IF NOT EXISTS pg_search;

    -- 앱·Flyway 전용 비슈퍼유저 역할. 볼륨이 비어있을 때만 도는 스크립트라 재실행을
    -- 가정하지 않아도 되지만, 방어적으로 존재 여부를 확인한다.
    -- DO $do$ ... $do$ 블록 대신 SELECT ... \gexec 패턴을 쓴다 — psql 은 달러 인용
    -- 문자열 안의 콜론을 PL/pgSQL 코드로 취급해 :'var'/:"var" 치환을 수행하지 않는다
    -- (D1 실측: DO 블록에 넣었더니 콜론이 리터럴 텍스트로 그대로 서버에 전달돼
    -- "syntax error at or near ':'" 로 실패했다). format() 의 %I(식별자 안전 인용)·
    -- %L(리터럴 안전 인용)로 만든 CREATE ROLE 문을 조건이 참일 때만 \gexec 로
    -- 실행하면 달러 인용을 아예 쓰지 않고도 동일한 효과를 낸다.
    SELECT format('CREATE ROLE %I NOSUPERUSER LOGIN PASSWORD %L', :'app_user', :'app_pw')
    WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'app_user')
    \gexec

    -- public 스키마 소유권을 앱 역할로 넘긴다. Flyway 가 CREATE TABLE/INDEX 를 하려면
    -- 스키마에 대한 CREATE 권한이 필요한데, 소유권 이전이 매 마이그레이션마다 별도
    -- GRANT 를 추가하지 않아도 되는 가장 단순한 방법이다(로컬 데모 스코프).
    ALTER SCHEMA public OWNER TO :"app_user";
    GRANT ALL PRIVILEGES ON DATABASE :"db_name" TO :"app_user";
EOSQL
