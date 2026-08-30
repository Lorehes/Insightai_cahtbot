-- V3__normalize_messages_finish_reason_to_lowercase.sql
-- BL-003 후속: messages.finish_reason 에 이미 저장된 대문자 값을 소문자로 정규화한다.
--
-- ⚠ 사용자 승인 대기 (전역 G5). 이 파일은 db/migration 에 있으므로 **애플리케이션을 다음에
--   기동하는 순간 Flyway 가 자동 적용한다.** 승인 전에 기동하지 않도록 주의한다.
--
-- 왜 필요한가:
--   BL-003(2026-08-30)이 ChatChunk.normalizeFinishReason 을 도입해 앞으로 저장되는 값은
--   소문자다. 그러나 그 이전에 저장된 행은 대문자로 남아, 같은 의미의 값이 대소문자로
--   영구 혼재하게 된다. finish_reason 으로 그룹핑하는 집계가 두 그룹으로 갈라진다 --
--   특히 eval-usage-dashboard:AC-20a("finish_reason 분포에 length 가 포함되면 판정 유보")가
--   대소문자에 직접 걸린다.
--
-- 적용 전 실측 (2026-08-30, 라이브 chatbot-db 컨테이너 직접 접속, 읽기 쿼리만):
--   SELECT finish_reason, count(*) FROM messages GROUP BY finish_reason;
--     -> 'STOP' 25행. messages 전체 50행 중 assistant 25행이 전부 이 값이며
--        NULL / 'error' / 'cancelled' / 소문자 값은 0행이었다.
--
-- 설계 판단:
--   'STOP' 을 하드코딩하지 않고 lower() 로 일반화한다. 위 실측 시점 이후 승인까지 사이에
--   다른 대문자 값(예: 'LENGTH')이 새로 저장됐을 수 있고, 그 경우에도 이 문장이 옳게 동작한다.
--   조건절에 finish_reason <> lower(finish_reason) 을 두어 **멱등**하다 -- 이미 소문자인 행은
--   건드리지 않으므로 재실행해도 결과가 같고 updated 행 수가 0 이 된다.

UPDATE messages
   SET finish_reason = lower(finish_reason)
 WHERE finish_reason IS NOT NULL
   AND finish_reason <> lower(finish_reason);
