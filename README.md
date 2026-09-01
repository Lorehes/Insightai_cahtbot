# insight-chat

사내 업무용 챗봇. Spring Boot + OpenRouter + 하이브리드 RAG.

## 스택

| 레이어 | 값 |
|---|---|
| JDK | Java 21 |
| 백엔드 | Spring Boot 4.1.1 |
| AI | Spring AI 2.0.1 |
| 빌드 | Maven (`./mvnw`) |
| DB | ParadeDB `0.25.4-pg18` = PostgreSQL 18.6 + pg_search + pgvector |
| 프론트 | Next.js (App Router) + TypeScript |
| LLM | OpenRouter — 채팅 `upstage/solar-pro4`, 임베딩 `baai/bge-m3`(1024d), 리랭크 `cohere/rerank-v3.5` |

## 사전 준비

1. `infra/.env.example` 을 `infra/.env` 로 복사한다.
2. 아래 값은 예제 파일에 **빈 값**으로 남아 있으므로 직접 채워야 한다.
   - `OPENROUTER_API_KEY` — https://openrouter.ai/keys 에서 발급
   - `DB_PASSWORD`, `APP_DB_PASSWORD` — 예제에 실값을 적으면 그대로 커밋되는 것을 막기 위해 비워둔다. `openssl rand -hex 24` 등으로 직접 생성해 채운다
   - `UPLOAD_DIR` — 문서 업로드 저장 경로. **절대 경로 + 슬래시(`/`)**만 사용한다(백슬래시는 Java properties 파서에서 이스케이프 문자로 소비된다). 드라이브 루트 바로 아래는 상속 ACL 문제로 피한다

각 항목의 상세 근거는 `infra/.env.example` 자체의 주석에 있다.

## 실행 순서

```
① cd infra && docker compose up -d      # service: db, container: chatbot-db, 127.0.0.1:5432
② cd backend && ./mvnw spring-boot:run  # 127.0.0.1:8080
③ cd frontend && npm run dev            # 127.0.0.1:3000
```

⚠ 프론트는 `-H 127.0.0.1` 로 바인딩된다. **`localhost:3000` 이 아니라 `127.0.0.1:3000` 으로 접속**해야 한다.

## 단일 오리진 프록시

브라우저는 `/api/*` 를 프론트와 같은 오리진(`127.0.0.1:3000`)으로 호출하고, `next.config.ts` 의 `rewrites` 가 이를 백엔드(`127.0.0.1:8080`)로 프록시한다. 그래야 `HttpOnly` + `SameSite=Lax` 세션 쿠키가 자동으로 실린다. 프록시 대상은 다음 4개 경로뿐이다.

- `/api/session`
- `/api/models`
- `/api/conversations`
- `/api/conversations/:path*`

## 테스트

```
cd backend && ./mvnw test
cd frontend && npm test
```

## 디렉터리

- `backend/` — `art.insightai.chatbot` 아래 `common`·`user`·`chat`·`rag`·`usage` 도메인 패키지로 분리
- `frontend/` — Next.js
- `infra/` — `docker-compose.yml`, `.env.example`, DB 초기화 스크립트

## 알려진 한계

- **문서를 삭제해도 과거 대화의 출처는 남는다.** 어떤 문서를 근거로 답한 대화가 있고 그 뒤 문서를
  삭제하면, 대화 이력에는 그 문서의 파일명과 본문 스니펫이 저장 시점 스냅샷 그대로 남아 계속 조회된다.
  삭제는 이후의 새 검색에만 적용된다. 이것은 결함이 아니라 선택이다 — 출처를 스냅샷으로 저장하면
  과거 답변의 근거를 재현할 수 있고, 참조만 저장하면 삭제가 소급되는 대신 근거가 사라진다.
  현재는 전자를 택했다. 민감한 문서를 지울 때는 대화 이력도 함께 다뤄야 한다.
- **PDF 의 페이지 번호는 재색인 이후 문서에만 붙는다.** 페이지 추출을 도입하기 전에 색인된 문서는
  페이지 값이 비어 있으며, 다시 업로드해야 채워진다.

## 문서

설계·Spec·리뷰 기록은 이 저장소가 아니라 별도 문서 폴더에 있다. 이 저장소에는 `README.md` 와 `CLAUDE.md` 만 둔다.
