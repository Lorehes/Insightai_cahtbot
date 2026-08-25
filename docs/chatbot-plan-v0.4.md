# 사내 업무용 챗봇 기획서 v0.4

작성일: 2026-08-25 · 목표일: 2026-09-03 (상부 보고·데모) · 담당: Loren (1인)
v0.3 대비: **D1 실측 결과 반영.** 추정이었던 항목 대부분이 확정으로 바뀌었고, 설계가 한 군데 단순해졌다.

---

## 0. 한 줄 요약

Spring Boot + OpenRouter 기반 사내 챗봇을 **9/3 데모까지 "범용 대화 + 하이브리드 RAG"** 범위로, 인증 없이 로컬 실행으로 만든다.
하이브리드 검색은 **ParadeDB 한 컨테이너** 안에서 해결한다(BM25 + pgvector). 데모의 핵심 무기는 **RAG ON/OFF 토글로 같은 질문을 두 번 던지는 것**이다.

**현재 상태: D1 완료 (2026-08-25 15:00).** 예정보다 반나절 빠르다.

---

## 1. D1에서 확정된 것

v0.3까지 "미확인" 또는 "확신도 80%"였던 항목들이 실측으로 정리됐다.

| 항목 | v0.3 | D1 실측 결과 |
|---|---|---|
| `pg_search` UUID key_field | 미확인 | **성공** → 테이블 설계 단순화 |
| 한국어 형태소 검색 | 문서상 지원 | **동작 확인** (부분어·조사·고유명사·제품코드) |
| ParadeDB 인덱스 문법 | 문서 간 상충 | **v2 (`USING paradedb` + `pdb.lindera(korean)`)** |
| Spring AI ↔ OpenRouter base-url | `/v1` 제외 (확신도 80%) | **`/v1` 포함이 정답** |
| 채팅 모델 | 미정 | **`upstage/solar-pro4`** |
| 임베딩 모델 id | 미검증 | **`baai/bge-m3` 실재 확인, 1024차원** |
| 모델별 단가 | 미확인 | **실측 확보** (12장) |
| 외부망(R1) | 최우선 리스크 | **해소** (`openrouter.ai` 200) |

### 1.1 D1에서 발견한 사실 (v0.3에 없던 것)

**① Spring AI 2.0은 공식 OpenAI Java SDK를 감싼다**

스택트레이스가 `com.openai.errors.*`, `com.openai.services.async.chat.*`, `org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient`를 보여준다. 1.x의 자체 WebClient 방식이 아니다.

파급 효과:
- **`base-url`에 `/v1`이 포함**되어야 한다. SDK가 뒤에 `/chat/completions`만 붙인다.
- 예외 타입이 `com.openai.errors.*` — 예외 처리 코드를 이 기준으로 짠다.
- HTTP 클라이언트가 OkHttp — 타임아웃·재시도 설정이 그쪽이다.
- OpenRouter 고유 헤더(`HTTP-Referer`, `X-Title`)를 넣으려면 SDK 클라이언트 커스터마이저가 필요하다.

**② OpenRouter가 응답에 비용을 직접 실어준다**

```json
"usage": { "prompt_tokens": 53, "completion_tokens": 10, "cost": 0.00000279,
           "prompt_tokens_details": { "cached_tokens": 0, "cache_write_tokens": 0 } }
```

단가표를 들고 토큰을 곱할 필요가 없다. `messages.cost_usd`에 이 값을 그대로 넣으면 모델을 바꿔도 계산식을 고칠 일이 없다.
**단 D2 확인 필요**: `cost`는 OpenAI 표준 스키마에 없는 OpenRouter 확장 필드라, SDK가 파싱에서 버릴 가능성이 높다. 버려지면 OkHttp 인터셉터로 원본 응답을 가로채야 한다.

`cached_tokens` / `cache_write_tokens`가 보이는 것은 **프롬프트 캐싱 지원**을 뜻한다. RAG는 시스템 프롬프트와 청크가 반복되므로 Phase 2 이후 비용 절감 여지가 크다.

**③ SSE로 raw String을 흘리면 공백이 사라진다**

SSE 프로토콜은 `data:` 뒤 첫 한 칸을 구분자로 소비한다. 토큰 `" 금요일"`을 그대로 보내면 수신 측에서 `"금요일"`이 되어 **단어 사이 공백이 전부 유실**된다.
→ 토큰을 JSON으로 감싸 보낸다. **인코딩 문제와 공백 문제가 동시에 해결된다.**

**④ 무료 모델은 개발용으로도 못 쓴다**

`:free` 모델은 전체 사용자 공유 풀을 쓴다. 내 사용량과 무관하게 429가 난다(D1에 실증). 개발·데모 모두 유료 모델을 쓴다.

---

## 2. 확정 전제

| 항목 | 값 | 확인 |
|---|---|---|
| JDK | Java 21 (Microsoft OpenJDK 21.0.12.1) | 실행 확인 |
| 백엔드 | Spring Boot **4.1.1** (Spring 7.0.9 / Tomcat 11.0.24 / Hibernate 7.4.5) | 기동 로그 |
| AI 연동 | Spring AI **2.0.1** (공식 OpenAI Java SDK 래핑) | 의존성 해석 |
| LLM 게이트웨이 | OpenRouter (채팅·임베딩·리랭크) | 200 응답 |
| 채팅 모델 | **`upstage/solar-pro4`** | 200 응답, 한국어 품질 양호 |
| 임베딩 | **`baai/bge-m3`** (1024차원) | 모델 목록 확인 |
| DB | **ParadeDB `0.25.4-pg18`** (PostgreSQL 18.6 / pg_search 0.25.4 / pgvector 0.8.4) | 컨테이너 healthy |
| 검색 | 하이브리드 (BM25 + 벡터 + RRF), 리랭커 선택 | 스파이크 통과 |
| 빌드 | Maven, 모노레포 (`backend/`, `frontend/`, `infra/`, `docs/`) | — |
| 패키지 | `art.insightai.chatbot` | — |
| 인증 | 없음 (익명 쿠키 세션) | 설계 확정 |
| 실행 | 로컬 (앱은 IntelliJ, DB는 Docker) | — |
| 문서 | 회사 소개 1건 (+1건 예정) | **미확보 — R4** |
| 인력·일정 | 1인 / 8영업일 (8/25~9/3) | D1 완료 |

### 2.1 Spring Boot 4에서 바뀐 이름들 (3.x 자료를 그대로 쓰면 안 됨)

| 3.x | 4.x |
|---|---|
| `spring-boot-starter-web` | **`spring-boot-starter-webmvc`** |
| `spring-boot-starter-test` 하나 | **모듈별** `...-webmvc-test`, `...-data-jpa-test`, `...-flyway-test`, `...-validation-test` |
| `flyway-core` + `flyway-database-postgresql` | **`spring-boot-starter-flyway`** + `flyway-database-postgresql` |
| `server.servlet.encoding.*` | **`spring.servlet.encoding.*`** (`mapping`만 `server.` 유지) |
| — | **`spring.http.converters.string-encoding-charset`** (StringHttpMessageConverter 전용) |

### 2.2 Spring AI 2.0에서 바뀐 것

- 프로퍼티에서 **`.options` 세그먼트 제거**: `spring.ai.openai.chat.options.model` → `spring.ai.openai.chat.model`
- 벡터스토어 아티팩트: **`spring-ai-starter-vector-store-pgvector`** (공식 Getting Started 페이지의 `spring-ai-starter-pgvector-store`는 오기. Maven Central 기준)
- `ChatMemory`의 `conversationId`가 **호출마다 필수**, `DEFAULT_CONVERSATION_ID` 제거
- 도구 호출이 함수 빈 자동 해석 → **명시적 `ToolCallback` 또는 `@Tool`**, `ToolCallAdvisor` → `ToolCallingAdvisor`
- 옵션 클래스 불변화(`copy()` → `mutate().build()`)

**웹 예제 대부분이 1.x 문법이다. 복붙하면 프로퍼티가 조용히 무시된다.**

---

## 3. 범위

### 3.1 In Scope

1. **대화** — SSE 스트리밍(JSON 청크), 모델 선택 3~5종, 이력 저장·재조회, 마크다운 렌더링
2. **하이브리드 RAG** — 업로드 → 파싱·청킹·임베딩·색인, BM25+벡터+RRF, **출처 표시**, **RAG ON/OFF 토글**
3. **익명 세션** — 쿠키 기반 대화 격리
4. **관리자(최소)** — 문서 목록·업로드·삭제, 토큰·비용 집계

### 3.2 Out of Scope

인증·SSO, 파일서버 자동 동기화, 부서별 권한 분리, 사내 시스템 연동, 온프레 배포, HA

---

## 4. 아키텍처

```
┌─────────────────────────────────────────┐
│  브라우저 — Next.js 채팅 UI  (D3)        │
└───────────────┬─────────────────────────┘
                │ SSE (JSON 청크)
┌───────────────▼─────────────────────────┐
│  Spring Boot 4.1.1  :8080                │
│   AnonymousSessionFilter (쿠키 UUID)     │
│   ChatService   ─ ChatClient + ChatMemory│
│   RagService    ─ RetrievalAugmentation  │
│     └ HybridDocumentRetriever (커스텀)   │
│         ├ BM25 검색   (pg_search)        │
│         ├ 벡터 검색   (pgvector)         │
│         ├ RRF 융합                       │
│         └ 리랭커     (OpenRouter)        │
│   IngestService ─ 비동기 상태머신         │
│   UsageService  ─ usage.cost 기록         │
└───┬──────────────────────────────┬───────┘
    │                              │
┌───▼──────────────────┐   ┌───────▼────────────┐
│ ParadeDB 0.25.4-pg18 │   │ OpenRouter         │
│  users/conversations │   │  /v1/chat/completions│
│  messages/documents  │   │  /v1/embeddings    │
│  vector_store_bgem3  │   │  /v1/rerank        │
│    ├ HNSW 인덱스     │   └────────────────────┘
│    └ BM25 인덱스     │
└──────────────────────┘
```

### 4.1 디렉터리

```
insight-chat/
├─ backend/                    Spring Boot (Maven)
│  ├─ http/                    IntelliJ HTTP Client (.http, private env json)
│  └─ src/main/
│     ├─ java/art/insightai/chatbot/
│     └─ resources/{application.yaml, db/migration/}
├─ frontend/                   Next.js (D3)
├─ infra/                      docker-compose.yml, .env, .env.example
├─ docs/                       기획서, 스파이크 기록
└─ .gitignore
```

패키지는 레이어드가 아니라 **도메인별**(`chat/`, `rag/`, `user/`, `usage/`, `common/`). 저장소나 검색 방식을 바꿀 때 변경 범위가 `rag/retrieval/` 하나에 갇힌다.

---

## 5. 데이터 모델 — v0.3에서 단순해짐

**UUID `key_field`가 되므로 테이블을 나누지 않는다.** v0.3의 `document_chunks` 분리 설계는 폐기한다.

```
users
  id, auth_provider('anonymous'|'works'), external_id, display_name, created_at

conversations
  id, user_id, title, model, rag_enabled, created_at, updated_at

messages
  id, conversation_id, role, content, model,
  prompt_tokens, completion_tokens, cost_usd,     -- OpenRouter usage.cost 를 그대로
  sources(jsonb), created_at

documents
  id, filename, title, mime_type, size_bytes,
  status(PENDING|PARSING|EMBEDDING|INDEXED|FAILED),
  error_message, chunk_count, created_at, indexed_at

vector_store_bgem3                     ← 검색의 중심 테이블 (스키마 주인은 Flyway)
  id uuid PK, content text, metadata json, embedding vector(1024)
  ├ HNSW 인덱스 (embedding vector_cosine_ops)
  └ BM25 인덱스 USING paradedb (id, (content::pdb.lindera(korean))) WITH (key_field='id')
```

### 5.1 스키마의 주인은 Flyway

`spring.ai.vectorstore.pgvector.initialize-schema: false`로 두고 **Flyway가 테이블과 두 인덱스를 모두 만든다.** PgVectorStore가 자기 마음대로 만들면 임베딩 모델 교체 시 차원이 안 맞는 테이블이 조용히 생성될 수 있다.

### 5.2 ParadeDB 제약 — 테이블당 인덱스 1개

```
ERROR: a relation may only have one ParadeDB index
```

`title`과 `content`를 각각 다른 토크나이저로 인덱싱할 수 없다. **하나의 인덱스에 여러 필드를 넣어야 한다.** D5 설계 시 유의.

---

## 6. API

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/session` | 익명 세션 발급·조회 |
| GET | `/api/models` | 사용 가능한 모델 목록 |
| POST | `/api/conversations` | 새 대화 |
| GET | `/api/conversations` | 내 대화 목록 |
| GET | `/api/conversations/{id}/messages` | 대화 이력 |
| POST | `/api/conversations/{id}/messages` | 질의 → **SSE (JSON 청크)** |
| DELETE | `/api/conversations/{id}` | 대화 삭제 |
| POST | `/api/admin/documents` | 업로드 (즉시 응답, 비동기 색인) |
| GET | `/api/admin/documents` | 문서 목록·상태 |
| DELETE | `/api/admin/documents/{id}` | 문서·청크 삭제 |
| GET | `/api/admin/usage` | 사용량·비용 |

### 6.1 SSE 청크 형식

raw String을 흘리면 인코딩이 깨지고 공백이 유실된다. **JSON으로 감싼다.**

```java
@PostMapping(value = "...", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ChatChunk> stream(...) { ... .map(ChatChunk::new); }

public record ChatChunk(String delta) {}   // D2에 conversationId, finishReason, usage 추가
```

```
data:{"delta":" 금요일"}
```

그리고 응답 charset을 강제한다.

```yaml
spring:
  servlet:
    encoding: { charset: UTF-8, force-response: true }
  http:
    converters: { string-encoding-charset: UTF-8 }
```

`@PostMapping(produces = ...+";charset=UTF-8")`은 **효과가 없다.** Spring MVC가 SSE 응답의 Content-Type을 덮어쓰면서 charset 파라미터를 떨어뜨린다.

---

## 7. 하이브리드 RAG 파이프라인

```
질의
 └→ CompressionQueryTransformer  (대화 맥락을 독립 질의로 압축)
      ├→ BM25 검색   (pg_search, Lindera korean, top 20)
      └→ 벡터 검색   (bge-m3 + pgvector HNSW, top 20)
             ↓
         RRF 융합   1/(k + rank), k=60, 가중치 조정 가능
             ↓
         리랭커     (OpenRouter /v1/rerank, top 20 → top 5)   ※선택
             ↓
         컨텍스트 주입 → LLM → 답변 + 출처
```

### 7.1 D1 스파이크 실측

```sql
'연차'          → '연차휴가는 입사일을 기준으로...'        score 1.32   ✅ 부분어
'회사'          → '...소프트웨어 개발 회사입니다.'         score 1.39   ✅ 조사 제거
'인사이트에이아이' → 정답 3.99 / 무관한 행 1.18            ⚠ 노이즈
'ES-2024-A'     → '제품코드 ES-2024-A 는 물류...'         score 8.21   ✅ 정확 매칭
```

- **`ES-2024-A`의 8.21점이 하이브리드의 존재 이유다.** 의미가 아니라 글자로 찾아야 하는 건 벡터가 약하고 BM25가 압도적으로 잡는다. 데모에서 이 예시를 그대로 쓴다.
- **고유명사 노이즈**: 형태소 분석기가 회사명을 쪼개면서 무관한 행이 1.18점으로 딸려왔다. **점수 임계값이 필요하다** (D7 튜닝 항목).

### 7.2 인제스천

| 단계 | 선택 | 비고 |
|---|---|---|
| 파싱 | Spring AI Tika DocumentReader | PDF/DOCX/PPTX/XLSX. **HWP 불가** |
| 청킹 | TokenTextSplitter | **오버랩 파라미터가 없다** (아래) |
| 임베딩 | `baai/bge-m3` 1024차원, $0.01/M | |
| 색인 | HNSW + BM25 (같은 테이블) | |

**TokenTextSplitter에 오버랩이 없다.** v0.3까지 "오버랩 15%"라고 적었던 것은 오류다. 실제 파라미터는 `chunkSize`(기본 800), `minChunkSizeChars`(350), `minChunkLengthToEmbed`, `maxNumChunks`, `keepSeparator`, `encodingType`, `punctuationMarks`.

→ 청크 경계에서 문맥이 잘리는 걸 오버랩으로 완화할 수 없다. D5에 선택한다.
- (a) `chunkSize`를 키워 경계 손실 비중을 낮춘다 (bge-m3는 8K 컨텍스트라 여유가 있다)
- (b) `TextSplitter`를 직접 구현한다

### 7.3 RRF 쿼리

```sql
WITH bm25 AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY pdb.score(id) DESC) AS r
  FROM vector_store_bgem3 WHERE content ||| :query LIMIT 20
),
vec AS (
  SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> :qvec) AS r
  FROM vector_store_bgem3 LIMIT 20
),
rrf AS (
  SELECT id, 1.0/(60 + r) * :w_bm25 AS s FROM bm25
  UNION ALL
  SELECT id, 1.0/(60 + r) * :w_vec  AS s FROM vec
)
SELECT v.id, v.content, v.metadata, SUM(s) AS score
FROM rrf JOIN vector_store_bgem3 v USING (id)
GROUP BY v.id, v.content, v.metadata
ORDER BY score DESC LIMIT 20;
```

D1 스파이크에서 **조인이 있으면 ParadeDB의 DataFusion 집계 가속이 꺼진다**는 경고를 확인했다.

```
WARNING: Aggregate Scan (DataFusion) not used: ... (table: join)
```

단일 테이블이 되면서 조인 대상이 자기 자신이 됐으므로 경고가 사라질 가능성이 있다. **D6에 확인한다.** 데모 규모에서는 무관하다.

### 7.4 리랭커

데모 기본은 끔(`app.rerank.enabled=false`). D7에 평가셋으로 ON/OFF를 비교해 결정한다.

| 모델 | 가격 | 비고 |
|---|---|---|
| `cohere/rerank-v3.5` | $0.001 / search | 100+ 언어 |
| `qwen/qwen3-embedding` 계열 리랭커 | $0.20 / M | 다국어 최적화 |
| `nvidia/llama-nemotron-embed-vl-1b-v2` | 무료 | 멀티모달 |

---

## 8. 확장성 안전장치 (7개)

| # | 내용 | 시점 | 상태 |
|---|---|---|---|
| #1 | 청크 metadata에 `dept`/`acl`을 `"ALL"`로라도 채우기 | D5 | 예정 |
| #2 | 벡터 테이블명에 모델명 박기(`vector_store_bgem3`), 차원·인덱스 프로퍼티화 | D1 | **완료** |
| #3 | 인제스천을 비동기 상태머신으로 | D5 | 예정 |
| #4 | ChatMemory 사용, Repository는 `messages` 테이블 위에 직접 구현 | D2 | 예정 |
| #5 | RAG는 `RetrievalAugmentationAdvisor`, 검색은 커스텀 `DocumentRetriever` | D6 | 예정 |
| #6 | `users.auth_provider` + `app.auth.mode=none\|works` | D2 | 프로퍼티만 완료 |
| #7 | 설정 전부 환경변수화 (`.env` + `spring.config.import`) | D1 | **완료** |

**#4 함정 재확인** — `JdbcChatMemoryRepository`를 그냥 쓰면 Spring AI가 전용 테이블을 만들어 대화가 두 곳에 중복 저장된다. 감사 로그·비용 집계와 LLM 컨텍스트의 진실의 원천이 갈린다. 인터페이스이므로 직접 구현한다(약 50줄).

**#7 보충** — `.env`는 두 파서가 읽는다(docker compose는 dotenv, Spring은 Java properties). `export KEY=`, `KEY="값"`, 값에 백슬래시를 쓰면 안 된다. **D5에 업로드 경로를 넣을 때 `C:\uploads`가 아니라 `C:/uploads`로 써야 한다.**

`spring.config.import`는 실행 위치(터미널=`backend/`, IntelliJ=프로젝트 루트)에 따라 달라지므로 두 경로를 모두 시도한다.

```yaml
spring:
  config:
    import:
      - optional:file:../infra/.env[.properties]
      - optional:file:./infra/.env[.properties]
```

`optional:`이 붙어 있으면 **파일을 못 찾아도 조용히 넘어가고 키만 빈 채로 기동된다.** 인증 에러가 나면 이 경로부터 의심한다.

---

## 9. 일정

### D1 (8/25 화) — **완료 ✅**

- 프로젝트 골격 (Boot 4.1.1 + Spring AI 2.0.1 + Maven, 패키지 `art.insightai.chatbot`)
- ParadeDB 컨테이너 (`0.25.4-pg18`, 태그 고정)
- 설정 외부화 (`.env` + `spring.config.import`)
- OpenRouter 스트리밍 (한글·공백 정상, JSON 청크)
- **하이브리드 스파이크 5항목 전부 통과**
- 모델 확정 (`upstage/solar-pro4`)

예정보다 반나절 빨랐다. **D2를 8/25 오후에 착수 가능.**

### D2 (8/26 수) — 대화 백엔드
Flyway 스키마(users/conversations/messages/documents/vector_store_bgem3 + 두 인덱스), 익명 세션 필터(#6), SSE JSON 청크 확장(conversationId·finishReason), **ChatMemory 자체 Repository(#4)**, 모델 선택, **usage.cost 기록(SDK가 버리는지 확인)**, `@ConfigurationProperties` 클래스
→ **완료 기준**: 대화가 이어지고 DB에 남으며, 비용이 기록된다

### D3 (8/27 목) — 채팅 UI
Next.js SSE 수신, 마크다운·코드블럭 렌더링, 대화 목록, 모델 선택, **nginx 단일 오리진 또는 Next rewrites**
→ **완료 기준**: 브라우저에서 사람이 쓸 만하다

### D4 (8/28 금) — 안정화 + RAG 선행 ⭐체크포인트
오전: 대화 기능 마감 → **여기서 멈춰도 "사내 ChatGPT"로 데모 가능**
오후: 문서 업로드 화면, Tika 파싱

### 주말 (8/29~8/30) — 버퍼

### D5 (8/31 월) — RAG ①: 인제스천
비동기 상태머신(#3), 청킹(오버랩 대안 결정), 임베딩·색인, metadata 규약(#1)
→ **완료 기준**: 문서를 올리면 청크 수가 표시되고 BM25·벡터 양쪽에 색인된다

### D6 (9/1 화) — RAG ②: 하이브리드 검색
`HybridDocumentRetriever`(RRF SQL), `RetrievalAugmentationAdvisor`(#5), `CompressionQueryTransformer`, 출처 표시, RAG ON/OFF 토글, `app.search.mode=vector|hybrid`
→ **완료 기준**: 두 모드 모두 동작하고 출처가 표시된다

### D7 (9/2 수) — 평가 + 튜닝 + 대시보드
평가셋 20문항, **vector/hybrid × rerank on/off 4조합 비교**, RRF 가중치·k·청크 크기·**점수 임계값** 튜닝, 사용량·비용 대시보드
→ **완료 기준**: 평가셋 통과 + 데모 모드를 데이터로 결정

### D8 (9/3 목 오전) — 리허설
시나리오 3회, **백업 영상 녹화**, 테더링 준비

---

## 10. 데모 시나리오 (9/3, 7분)

### ① 범용 대화 (1분)
사내 공지문 다듬기. D1에서 확인한 실제 출력이 근거다 — solar-pro4가 기본형/양해요청형/공지사항형 세 가지를 알아서 제시했다.

### ② RAG ON/OFF 비교 (3분) ← **핵심**

| | 질문 | 기대 결과 |
|---|---|---|
| RAG **OFF** | "우리 회사 설립일이 언제야?" | 모르거나 엉뚱한 답 |
| RAG **ON** | 같은 질문 | 정확한 답 + **출처: 회사소개.pdf p.3** |

**Q4("그냥 ChatGPT 사면 되잖아")에 대한 유일한 실증이다.** 말로 설명하지 말고 화면으로 보여준다.

### ③ 환각 안 함 증명 (1분)
문서에 없는 것을 묻는다 → "제공된 문서에서 찾을 수 없습니다."

### ④ 멀티턴 (30초)
"그럼 그때 직원은 몇 명이었어?" — 대명사만으로 맥락 유지.

### ⑤ 관리자 + 로드맵 (1.5분)
업로드 → 색인 상태 → 즉시 반영. 비용 집계.
"1건이지만 1만 페이지여도 구조는 같고, 색인 비용은 $0.05다."

### ⑥ 질문 나오면 꺼낼 카드 — 하이브리드
"제품코드처럼 글자로 찾아야 하는 건 벡터가 약합니다." → D1 스파이크의 `ES-2024-A` 8.21점 사례를 보여준다.

---

## 11. 평가셋 (D7)

```yaml
- question: "회사 설립일이 언제야?"
  expect_source: "회사소개.pdf"
  expect_contains: ["2011"]
- question: "작년 매출이 얼마야?"
  expect: NOT_FOUND        # 문서에 없음 → "모른다"가 정답
```

문서에 **있는 질문 15 + 없는 질문 5**. 없는 질문의 정답은 "모른다"이며, 이를 측정하지 않으면 환각이 조용히 늘어난다.
측정 대상: `vector` vs `hybrid` × `rerank on/off` = 4조합.

---

## 12. 상부 예상 질문 대응

### Q1. "돈 얼마 드는데?" — **실측 단가 확보 (2026-08-25)**

전제: 300명 × 15질의/일 × 20영업일 = 90,000질의/월, RAG 포함 입력 3,000 / 출력 600 토큰
→ 월 입력 270M, 출력 54M 토큰

| 모델 | 입력 $/M | 출력 $/M | **월 비용** |
|---|---|---|---|
| **`upstage/solar-pro4`** | 0.03 | 0.12 | **약 $15** |
| `google/gemini-2.5-flash-lite` | 0.10 | 0.40 | 약 $49 |
| `openai/gpt-5-mini` | 0.25 | 2.00 | 약 $176 |
| `google/gemini-2.5-flash` | 0.30 | 2.50 | 약 $216 |
| `anthropic/claude-haiku-4.5` | 1.00 | 5.00 | 약 $540 |

부대 비용:
- **임베딩**: bge-m3 $0.01/M → 문서 1만 페이지(약 500만 토큰) 전체 색인에 **$0.05**
- **리랭크**: `cohere/rerank-v3.5` $0.001/search → 월 90,000질의에 **$90**

좌석당 과금 SaaS를 300명에게 주면 **월 수천 달러**다. 한 자릿수 배가 아니라 **10~100배 차이**다. 이 표 한 장이 Q1과 Q4를 동시에 해결한다.

**함께 말할 것**: 현재 데모는 인증이 없다. 전사 오픈 시 **인증 + 사용자별 쿼터가 전제**이며, 없으면 비용 통제가 불가능하다.

### Q2. "우리 문서가 외부로 나가는 거 아니냐?"
- 나가는 것은 **질문 + 검색된 청크 몇 개**. 문서 전체나 파일서버 권한이 아니다.
- OpenRouter는 **ZDR(Zero Data Retention)** 과 데이터 레지던시 라우팅을 공식 지원한다.
- 그래도 안 되면 로컬 LLM(vLLM) — GPU 비용과 한국어 품질 저하를 감수해야 한다.

### Q3. "답이 틀리면?"
출처 표시로 검증 / 근거 없으면 "모른다"(데모 ③) / 전건 DB 보관 / 최종 판단은 사람

### Q4. "그냥 ChatGPT Team 사면 되잖아?"

| | 외부 SaaS | 자체 구축 |
|---|---|---|
| 사내 문서 Q&A | 업로드 수동, 권한 통제 어려움 | 사내 문서 기반, 권한 설계 가능 |
| 검색 방식 | 블랙박스 | 하이브리드·리랭커까지 직접 조정 |
| 사내 시스템 연동 | 불가 | 가능 (Phase 4) |
| 비용 구조 | 좌석당 고정 | **사용량 종량제 (월 $15~)** |
| 모델 종속 | 벤더 1곳 | OpenRouter로 언제든 교체 |
| 감사·로그 | 제한적 | 전건 보관 |

### Q5. "언제 전사에 열 수 있냐?" → 14장

---

## 13. 리스크

| # | 항목 | 상태 | 영향 | 대응 |
|---|---|---|---|---|
| ~~R1~~ | ~~데모 장소 외부망 차단~~ | **해소** (200) | — | 단, 다른 층·게스트망에서 데모하면 재확인 |
| ~~R2~~ | ~~`pg_search` UUID key_field~~ | **해소** (성공) | — | 단일 테이블 설계 확정 |
| **R4** | **회사 소개 문서 미확보** | **미확보 — 최우선** | RAG 데모 불가 | 즉시 요청. HWP면 PDF 변환 |
| R3 | ParadeDB **AGPL 라이선스** | 확인됨 | Phase 2 사내 배포 시 법무 검토 | 로컬 데모는 무관. 막히면 PGroonga/OpenSearch |
| R5 | 문서 1건에서 BM25의 IDF가 무의미 | 예상 | 하이브리드가 답을 나쁘게 할 수 있음 | 데모는 벡터 단독 기본, D7에 데이터로 결정 |
| R6 | 고유명사 노이즈 (스파이크 5-c) | **실증** | 무관한 청크 유입 | D7에 점수 임계값 튜닝 |
| R7 | Spring AI 2.0 자료 부족 | 확인됨 | 트러블슈팅 지연 | 1.x 예제 복붙 금지 |
| R8 | 인증 없음 → 전사 오픈 시 비용 통제 불가 | 설계상 | Phase 2 필수 | 인증 + 쿼터 |
| R9 | NAVER WORKS OAuth 엔드포인트 | 미확인 (지원은 85%) | Phase 2 지연 | **SSO 활성 조직은 authorize에 `domain` 필수** |
| R10 | 온프레 서버·방화벽 | 미확보 | Phase 2 지연 | Phase 2 시작 시 티켓 |
| **R11** | **무료 모델 레이트 리밋** | **실증** (429) | 개발·데모 중단 | `:free` 금지. 유료 모델 사용 |
| **R12** | **`usage.cost`를 SDK가 버릴 가능성** | 미확인 | 비용 집계 불가 | D2에 확인. 버리면 OkHttp 인터셉터 |
| **R13** | **개발 PC의 Docker/WSL2 의존** | **해소** | — | D0 체크리스트에서 누락했던 항목 |

---

## 14. 9/3 이후 로드맵

| 단계 | 기간 | 내용 |
|---|---|---|
| Phase 2 | 9월 | NAVER WORKS SSO, 사용자별 쿼터, 온프레 배포, **AGPL 검토**, 소수 팀 파일럿 |
| Phase 3 | 10월 | 파일서버 자동 동기화, 부서별 권한 분리(#1 회수), 리랭커 상시 적용, **프롬프트 캐싱** |
| Phase 4 | 11월~ | 사내 시스템 연동(function calling), 전사 오픈 |

---

## 부록 A. v0.3 → v0.4 정정 목록

| # | 항목 | 정정 내용 |
|---|---|---|
| 1 | TokenTextSplitter | 오버랩 파라미터 없음 (v0.1~v0.3 오류) |
| 2 | Spring Boot 4 스타터 | `starter-web` → `starter-webmvc`, 테스트 스타터 모듈화, Flyway 스타터 |
| 3 | Spring AI 2.0 구조 | 공식 OpenAI Java SDK 래핑 |
| 4 | base-url | `/v1` **포함** (v0.3에서 제외라고 한 것은 오류) |
| 5 | 벡터스토어 아티팩트 | `spring-ai-starter-vector-store-pgvector` |
| 6 | D1 Docker Compose | app+db+nginx → **db 단독** (nginx는 D3) |
| 7 | D0 선행조건 | **Docker/WSL2 설치 누락** — 발견 |
| 8 | PostgreSQL 18 볼륨 | `/var/lib/postgresql/data` → `/var/lib/postgresql` |
| 9 | 이미지 태그 | `latest` → `0.25.4-pg18` 고정 |
| 10 | 테이블 설계 | 분리(chunks+vector) → **단일 테이블** |
| 11 | ParadeDB 제약 | 테이블당 인덱스 1개 |
| 12 | RRF 조인 | DataFusion 가속 미적용 경고 |
| 13 | usage.cost | OpenRouter가 직접 제공 |
| 14 | SSE 형식 | raw String → **JSON 청크** (인코딩+공백) |
| 15 | 인코딩 프로퍼티 | `server.servlet.encoding.*` → `spring.servlet.encoding.*` |
| 16 | 모델 | `upstage/solar-pro4` 확정 + 실측 단가표 |
| 17 | 리스크 | R1·R2·R13 해소, R11·R12 신규 |

## 부록 B. 확인한 사실과 근거

| 사실 | 확신도 | 근거 |
|---|---|---|
| `pg_search` Lindera 한국어(`pdb.lindera(korean)`) 동작 | **100%** | D1 스파이크 실행 |
| `pg_search` UUID key_field 지원 | **100%** | D1 스파이크 실행 |
| ParadeDB 테이블당 인덱스 1개 제한 | **100%** | D1 스파이크 에러 메시지 |
| OpenRouter base-url에 `/v1` 포함 | **100%** | 401(경로 도달) vs 404(경로 없음) 비교 |
| `upstage/solar-pro4` 동작·한국어 품질 | **100%** | 200 응답 + 출력 확인 |
| `baai/bge-m3` 실재, 1024차원 | 95% | `/v1/embeddings/models` 조회 |
| OpenRouter `usage.cost` 제공 | **100%** | 200 응답 본문 |
| 무료 모델 공유 풀 레이트 리밋 | **100%** | 429 응답 메타데이터 |
| 모델별 단가 | 95% | `/v1/models` 조회 (2026-08-25) |
| Spring Boot 4.1.1 / Spring AI 2.0.1 동작 | **100%** | 기동 로그 |
| ParadeDB AGPL | 85% | ParadeDB 공식 블로그 |
| OpenRouter ZDR | 90% | 공식 문서 |
| NAVER WORKS OAuth 2.0 | 85% | 개발자 문서 (엔드포인트 본문 미확인) |
| `usage.cost`를 Spring AI가 노출하는지 | **미확인** | D2에 확인 |
| PGroonga·pg_bigm 한국어 실효성 | **미확인** | 대안 검토 시 필요 |
