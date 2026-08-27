# insight-chat — 프로젝트 규약

사내 업무용 챗봇. Spring Boot + OpenRouter + 하이브리드 RAG.
전역 규약(`~/.claude/CLAUDE.md`)을 따르며, **아래 항목은 전역보다 우선한다.**

---

## DOC_ROOT

```
DOC_ROOT: Z:\Work\02.insight-chat
```

전역 규약의 해석 순서 1번(프로젝트 CLAUDE.md 명시)에 해당하므로, 이하 규칙은 보지 않는다.

| 위치 | 내용 |
|---|---|
| `{DOC_ROOT}/` 루트 | 기획서·분석 자료 등 사람이 쓴 문서 |
| `{DOC_ROOT}/docs/` | 하네스 산출물 전용 (specs, dev-log, review, git-history, harness) |
| 코드 저장소 | **문서를 두지 않는다.** README 와 이 파일만 예외 |

`Z:` 는 매핑 드라이브다. 연결되지 않았으면 하네스 작업을 진행하지 말고 연결을 요청한다.

---

## 스택 (D1 실측 확정, 2026-08-25)

| 레이어 | 값 |
|---|---|
| JDK | Java 21 (Microsoft OpenJDK 21.0.12.1) |
| 백엔드 | Spring Boot **4.1.1** / Spring 7.0.9 / Tomcat 11 / Hibernate 7.4.5 |
| AI | Spring AI **2.0.1** |
| 빌드 | **Maven** (`./mvnw`) |
| DB | **ParadeDB `0.25.4-pg18`** = PostgreSQL 18.6 + pg_search 0.25.4 + pgvector 0.8.4 |
| 프론트 | Next.js (App Router) + TypeScript — D3 생성 예정 |
| LLM | OpenRouter — 채팅 `upstage/solar-pro4`, 임베딩 `baai/bge-m3`(1024d), 리랭크 `cohere/rerank-v3.5` |
| 실행 | 앱은 IntelliJ/`mvnw`, DB는 Docker Compose (`infra/`) |

베이스 패키지 `art.insightai.chatbot`.

---

## 반드시 지킬 사실 (틀리면 조용히 깨진다)

전부 D1에 실측으로 확인했다. **웹 자료와 어긋나면 이 표가 맞다.**

| # | 사실 |
|---|---|
| 1 | Spring AI 2.0 은 **공식 OpenAI Java SDK(`com.openai.*`) 래핑**이다. 예외 타입도 `com.openai.errors.*`, HTTP 클라이언트는 OkHttp |
| 2 | 그래서 `spring.ai.openai.base-url` 에 **`/v1` 을 포함**한다 (`https://openrouter.ai/api/v1`). 빼면 404 |
| 3 | Spring AI 2.0 프로퍼티에서 **`.options` 세그먼트가 제거**됐다. `chat.options.model` ✗ → `chat.model` ✓ |
| 4 | Spring Boot 4 스타터 이름: `starter-web` ✗ → **`starter-webmvc`** ✓. 테스트는 모듈별 (`...-webmvc-test` 등) |
| 5 | 인코딩 프로퍼티는 `server.servlet.encoding.*` ✗ → **`spring.servlet.encoding.*`** ✓ |
| 6 | SSE 는 **raw String 금지, JSON 청크**. `data:` 뒤 첫 공백이 소비돼 단어 간 공백이 유실된다 |
| 7 | `@PostMapping(produces = ...+";charset=UTF-8")` 은 **효과 없다**. Spring MVC 가 SSE Content-Type 을 덮어쓴다 |
| 8 | ParadeDB 는 **테이블당 인덱스 1개**. 여러 필드는 하나의 인덱스에 넣는다 |
| 9 | 한국어 BM25 문법: `USING paradedb (id, (content::pdb.lindera(korean))) WITH (key_field='id')` |
| 10 | PostgreSQL 18+ 볼륨은 `/var/lib/postgresql/data` ✗ → **`/var/lib/postgresql`** ✓ |
| 11 | `TokenTextSplitter` 에 **overlap 파라미터가 없다**. `chunkSize`/`minChunkSizeChars` 로 조절 |
| 12 | OpenRouter 응답 `usage.cost` 에 실제 비용이 실려온다. 단 SDK 가 버릴 수 있으니 D2 에 확인 |
| 13 | `annotationProcessorPaths` 블록이 있으면 **거기 나열된 프로세서만** 동작한다. 의존성 추가만으로는 부족 |

### 금지

| # | 금지 |
|---|---|
| P1 | **모델 id 를 추측해서 쓰지 않는다.** `GET /api/v1/models` 로 조회해 실재를 확인한다 |
| P2 | **`:free` 모델 사용 금지.** 사용자 공유 풀이라 내 사용량과 무관하게 429 가 난다 |
| P3 | Spring AI **1.x 예제를 복붙하지 않는다.** 프로퍼티가 조용히 무시된다 |
| P4 | `paradedb/paradedb:latest` 사용 금지. 태그를 고정한다 |

---

## 계층·패키지

전역 §7.1(Controller / Service / Repository) 3계층을 따른다. 패키지는 **레이어가 아니라 도메인**으로 나눈다.

```
art.insightai.chatbot
├─ common/{config, web, session}
├─ user/
├─ chat/{api, domain, repository, service}
├─ rag/{api, ingest, retrieval, domain}
└─ usage/
```

**근거**: 저장소(ParadeDB→PGroonga)나 검색 방식(vector→hybrid)을 바꿀 때 변경 범위가 `rag/retrieval/` 하나에 갇힌다. 레이어드면 같은 변경이 세 패키지에 흩어진다.

---

## 테스트

| 항목 | 값 |
|---|---|
| 프레임워크 | JUnit 5 (`spring-boot-starter-*-test`) |
| 실행 | `./mvnw test` — 특정 테스트는 `./mvnw test -Dtest=ClassName` |
| 통합 테스트 | `@SpringBootTest`. DB 필요 시 `infra` 컨테이너가 떠 있어야 한다 |

### 증거 원장 명명 규약 (전역 §6.1 의 Java 매핑)

전역 규약의 `it('[<slug>:AC-2] ...')` 는 JS 전제다. **이 프로젝트는 `@DisplayName` 을 쓴다.**

```java
@Test
@DisplayName("[chat-stream:AC-2] 동일 conversationId 재요청 시 이력이 중복되지 않는다")
void doesNotDuplicateHistoryOnRepeatedConversationId() { ... }
```

- AC 번호는 **`@DisplayName` 맨 앞 대괄호**에 넣는다. 메서드명이 아니다
- `@DisplayName` 문구는 한국어 허용 (읽는 사람이 한국어 화자)
- dev-qa-tester 는 이 형식에서 원장을 파생시킨다

### RAG 품질 AC

D7 평가셋(문서에 있는 질문 15 + 없는 질문 5)이 그대로 증거 원장이 된다.
"없는 질문"의 정답은 **"모른다"** 이며, 이걸 측정하지 않으면 환각이 조용히 늘어난다.

---

## 주석

전역 §7.3 형식(`[Purpose]/[Rationale]/[Side effects]/[Caution]/[AC]`)을 따른다.

| 대상 | 언어 |
|---|---|
| `.java` | **영어** (전역 규약) |
| `.yaml`, `.yml`, `.gitignore`, `.gitattributes`, `Dockerfile` 등 설정 파일 | **한국어 허용** |

설정 파일을 예외로 두는 근거는 전역 §7.3 제외 목록 4번("설정 파일")이다. 주석 작성 의무 자체가 없는 대상이므로 언어 규약도 적용하지 않는다. 다만 **왜 그렇게 설정했는지**는 계속 남긴다 — 오늘 겪은 함정 대부분이 설정 한 줄에서 나왔다.

---

## 커밋

전역 §8 형식을 따른다. **scope 는 아래 목록에서만 고른다.**

| scope | 범위 |
|---|---|
| `chat` | 대화 API·서비스·이력 |
| `rag` | 인제스천·검색·리랭크 |
| `user` | 익명 세션·사용자 |
| `usage` | 토큰·비용 집계 |
| `common` | 설정·필터·공통 웹 |
| `infra` | docker-compose, nginx, 환경변수 |
| `frontend` | Next.js |
| `test` | 여러 도메인이 공유하는 테스트 인프라·픽스처(`backend/src/test/java/.../support/` 등) — 특정 도메인 패키지 하나로 좁혀지지 않는 테스트 전용 변경 |
| `docs` | 문서만 |

`test`는 2026-08-27 INC-006(dev-log `scope` 필드에 이 목록에 없는 "test"가 14회 반복 사용된 것을 뒤늦게 발견)에 따라 추가됐다. `common`("설정·필터·공통 웹")은 프로덕션 공통 계층을 가리키므로 테스트 전용 인프라와는 성격이 다르다고 판단해 새 값으로 분리했다.

---

## 리뷰

전역 §9 임계값을 그대로 쓴다(덮어쓰지 않는다).

추가 필수 확인 항목:

| 축 | 이 프로젝트 고유 |
|---|---|
| security | OpenRouter API 키가 코드·프론트·커밋 대상 파일에 없는지. `.env` 계열이 gitignore 되는지 |
| correctness | 위 "반드시 지킬 사실" 13개 위반 여부 |
| performance | RAG 쿼리에 점수 임계값이 있는지 (없으면 무관한 청크가 딸려온다 — D1 스파이크 5-c 에서 실증) |

---

설계 전체는 `{DOC_ROOT}/chatbot-plan-v0.4.md` 에 있다. 이 파일은 **규약만** 담고 설계를 중복하지 않는다.
