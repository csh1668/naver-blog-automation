# 서브 프로젝트 2 — 채팅형 글쓰기 설계 (초안)

- 일자: 2026-08-28
- 근거: `요구사항.md` v0.1 + §5 변경 이력(1차·2차), `docs/design-guide.md` §8, SP1 스펙(`2026-08-28-publish-pipeline-design.md`)
- 범위: FR-2 (API 키 관리·PIN·키 로테이션·모델 다운그레이드), FR-4 (채팅 안에서의 사진·줄거리·음성 입력, 자동 임시 저장), FR-5 (글 생성), FR-6 (검토·부분 수정), FR-10.2 (초안 = 대화 세션), **FR-11 (개인화: 메모리, 자료 검색 툴, 고품질 프롬프트)**
- 범위 밖 (SP3): FR-3 (블로그에서 스타일 프로필 추출), FR-9 (진단), FR-1.4 본격 알림. 단, 메모리 저장소는 SP3의 스타일 프로필이 들어갈 자리를 미리 마련한다.
- 상태: **사용자 1차 검토 반영(2026-08-28)** — 키 접두 검사 제거, 도구 진행 표시, 화자 40대 여성, 3단 채팅 화면, 네이버 우선 검색, 메모리 자동 저장, 프롬프트 편집 가능, 글 길이 기본값 유지.

## 1. 목표

사용자가 사진과 한두 문장의 아이디어를 채팅으로 주면, 어시스턴트가 "이렇게 써 볼까요?" 계획을 제안하고 2~3턴 안에 다듬은 뒤, "초안 작성" 요청 시 오른쪽 패널의 스마트에디터에 완성 글을 채워 넣는다. 글의 품질은 (1) 정교한 시스템 프롬프트, (2) 사용자 개인화 메모리, (3) 필요할 때만 쓰는 웹 자료 검색으로 확보한다.

성공 기준:
1. 키가 없으면 글쓰기에 들어갈 수 없고, 관리자 화면에서 여러 키를 한 번에 붙여넣어 등록·검증할 수 있다.
2. 사진 5장 + 두 문장 아이디어로 시작해 3턴 이내에 초안이 에디터에 들어가고, SP1 파이프라인으로 발행된다.
3. "문단 2를 더 짧게" 같은 부분 수정 요청이 채팅에서 처리되어 에디터에 재주입된다.
4. 429/한도 초과 시 키 로테이션 → 모델 다운그레이드 순으로 자동 복구되고, 전부 실패하면 사용자 언어로 안내한다.
5. 메모리 항목이 프롬프트에 반영되고, 사용자가 관리 화면에서 읽고 지울 수 있다.
6. 앱을 껐다 켜도 대화가 그대로 남아 이어서 쓸 수 있다.

## 2. 추가 기술 요소

| 영역 | 선택 | 비고 |
|---|---|---|
| LLM 호출 | OkHttp + kotlinx-serialization으로 Gemini REST 직접 호출. **항상 `streamGenerateContent?alt=sse` 로 스트리밍**(사용자 결정): `say` 는 부분 JSON에서 점진 추출해 말풍선에 타이핑되듯 표시, `plan`/`post` 는 완료 후 렌더링, 도구 호출 청크는 누적 후 실행 | 공식 SDK 미사용 (SP1 결정 유지). 새 Interactions API 대신 무상태 generateContent 계열을 쓴다(로컬 영속이 필요하고 문서가 완전함) |
| 구조화 출력 | `responseMimeType: application/json` + `responseSchema` | 대화 턴 응답과 `PostContent`를 스키마로 강제 |
| 도구 호출 | Gemini function calling (`tools`) | `web_search`, `open_page`, `remember` 3개 |
| 키 보관 | Android Keystore AES-GCM 키로 암호화한 JSON을 DataStore에 저장 | FR-2.2 |
| 음성 입력 | `SpeechRecognizer` (ko-KR) | FR-4.3 |
| 사진 첨부 | Photo Picker → 세션 캐시에 1024px JPEG 복사(프롬프트용) + 원본 Uri 영속 권한(발행용) | SP1 `ImagePreparer` 재사용 |
| 검색 툴 | 화면 밖 `WebView`(데스크톱 UA)로 검색 결과·본문 텍스트 추출 | grounding 미사용 |
| 저장 | Room: `chat_session`, `chat_message`, `memory_item`, `api_key`(암호화 blob은 DataStore) | |

## 3. 패키지 구조 (SP1에 추가)

```
com.csh.blogwriter
├─ llm/
│  ├─ GeminiClient.kt          REST 호출, 멀티모달 parts, JSON 스키마, 함수 호출 루프
│  ├─ GeminiModels.kt          요청/응답 DTO (contents, parts, tools, functionCall/Response, usage)
│  ├─ ApiKeyStore.kt           키 목록 암호화 저장/조회/삭제, 유효성 검사 결과 캐시
│  ├─ ApiKeyParser.kt          붙여넣은 텍스트 → 키 후보 목록 (순수 Kotlin)
│  ├─ KeyRotator.kt            키 순환·쿨다운·모델 다운그레이드 정책 (순수 Kotlin)
│  └─ ModelPolicy.kt           기본/대체 모델 ID, 온도 등 설정
├─ research/
│  ├─ WebResearchTool.kt       web_search / open_page 구현 (WebView + JS 추출)
│  └─ assets/research_extract.js
├─ memory/
│  ├─ MemoryStore.kt           memory_item CRUD, 프롬프트용 요약 문자열
│  └─ MemoryExtractor.kt        발행 완료 시 LLM으로 새 기억 항목 추출 → 즉시 저장 + 채팅에 "이런 점을 기억해 둘게요" 보고
├─ chat/
│  ├─ ChatRepository.kt        세션·메시지 영속
│  ├─ ConversationEngine.kt    프롬프트 조립 + 턴 실행 + 툴 루프 + 응답 파싱 (UI 무관)
│  ├─ TurnResponse.kt          어시스턴트 턴 응답 스키마
│  └─ PromptBuilder.kt         시스템 프롬프트(§8)와 컨텍스트 조립
├─ ui/chat/                    ChatScreen(2단), Composer, MessageBubble, PlanCard, QuickReplyChips
├─ ui/admin/                   PinGate, SettingsScreen, ApiKeysScreen, MemoryScreen, ModelsScreen
└─ (SP1 그대로) ui/publish/PublishPanel, publish/*, session/*, data/*
```

## 4. API 키 관리 (FR-2)

### 4.1 입력 흐름
- `ApiKeysScreen`: 여러 줄 `AppTextField` 하나 + "등록" CTA. 붙여넣기 즉시 아래에 후보 칩이 나타난다.
- `ApiKeyParser.parse(text): List<String>`: 줄바꿈·쉼표·공백·세미콜론으로 분리 → 앞뒤 따옴표/`key=` 접두 제거 → 길이 20자 이상이고 공백이 없는 토큰을 모두 후보로 채택(접두 형태 검사는 하지 않는다 — 최근 발급 키는 `AQ.Ab8RN6…` 형태로 바뀌었고 앞으로도 바뀔 수 있다) → 중복 제거, 이미 등록된 키 제외. 유효성은 오직 검증 호출로 판단한다.
- "등록" 탭 → 후보마다 검증 호출(`GET /v1beta/models?key=…`, 5초 제한) → 결과 칩: 유효/무효(401·403)/한도(429는 유효로 간주)/네트워크 오류(재시도 가능). 유효 키만 저장. 
- 목록 화면: 키는 `AIza…마지막 4자` 로만 표시, 삭제 버튼(ConfirmSheet), 마지막 검증 시각, 최근 429 시각.

### 4.2 보관 (FR-2.2)
- `ApiKeyStore`: `KeyStore` 별칭 `blogwriter.apikeys`의 AES-256-GCM 키로 `List<ApiKey(id, secret, addedAt, lastOkAt, lastLimitedAt)>` JSON을 암호화해 DataStore(바이트 배열 base64)에 저장. IV는 값 앞에 붙인다. 메모리에는 복호화된 목록을 `StateFlow`로 유지.
- 백업 제외(`allowBackup=false` 유지), 로그에 절대 출력하지 않음.

### 4.3 로테이션과 다운그레이드 (FR-2.4, 2.6) — `KeyRotator` (순수 Kotlin, 테스트 대상)
```
상태: keys[]: {id, cooldownUntil}, models[]: [primary, fallback1, …], modelCooldownUntil[model]
next(now): 
  for model in models (primary부터):
    if modelCooldownUntil[model] > now: continue
    for key in keys (round-robin 시작점 = 마지막 성공 키 다음):
      if key.cooldownUntil <= now: return (key, model)
  return null  → 사용자에게 "지금은 글을 만들 수 없어요. 잠시 후 다시 시도해 주세요." (가장 이른 쿨다운 해제 시각 표시)
onResult(key, model, outcome):
  429/RESOURCE_EXHAUSTED → key.cooldownUntil = now + 60s; 같은 모델에서 모든 키가 쿨다운이면 modelCooldownUntil[model] = now + 10분
  401/403(키 무효) → key.disabled = true, 관리자 화면 배지
  5xx/네트워크 → 1회 재시도 후 다음 키
  성공 → 해당 키를 다음 시작점으로
```
- 모델 목록(설정 가능). 2026-08-28 공식 문서 기준 기본값: primary `gemini-3.7-flash`, fallback `gemini-3.5-flash-lite` (모델 페이지: ai.google.dev/gemini-api/docs/models). 원복은 자동(모델 쿨다운 만료 시 primary부터 다시 시도).
- **한도 (사용자 실측, 2026-08-28)**: `gemini-3.7-flash` RPM 5 / TPM 250k / RPD 20, `gemini-3.5-flash-lite` RPM 15 / TPM 250k / RPD 500. 한도는 프로젝트 단위이며, 사용자가 준비한 키 7개는 모두 서로 다른 계정·프로젝트에서 발급되었으므로 로테이션이 유효하다(하루 예산: 3.7-flash 140회, flash-lite 3,500회). 글 1편당 8~12회 호출을 가정하면 하루 2편에 약 5배 여유. `KeyRotator` 기본값: 429 시 키 쿨다운 60초(RPM), 같은 키가 하루 20회 성공 후에는 자정(태평양 시간 기준 리셋)까지 그 키를 마지막 순위로 내린다(RPD 선제 회피). 관리자 키 등록 안내에는 "키마다 다른 프로젝트에서 발급" 문구를 유지한다.

### 4.4 게이팅 (FR-2 보완)
- `ApiKeyStore.hasUsableKey: Flow<Boolean>` (등록·비활성 아님·검증 성공 이력). false면 Home CTA 비활성 + InlineBanner "글을 쓰려면 관리자가 열쇠를 등록해야 해요" (기술 용어 회피). 채팅 화면에 직접 진입 시(초안 재개 등)도 같은 배너.
- 관리자 진입: 홈 톱니 → `PinGate`. 최초 진입 시 PIN(4~6자리) 설정, SHA-256+salt로 DataStore 저장, 5회 실패 시 30초 잠금.

## 5. 대화 모델과 영속 (FR-4.4, FR-6.4, FR-10.2)

```kotlin
@Entity chat_session(id, title?, createdAt, updatedAt, status: DRAFTING|PUBLISHING|PUBLISHED|ARCHIVED, pendingJobId?, publishedUrl?)
@Entity chat_message(id, sessionId, seq, role: USER|ASSISTANT|SYSTEM, kind: TEXT|PHOTOS|PLAN|POST|QUICK_REPLY|STATUS, payloadJson, createdAt)
```
- `PHOTOS` payload: `[{uri, ref, width, height, takenAt}]` (ref = `img_001…`, SP1 규칙). 사진 순서 변경은 메시지 payload 갱신.
- `PLAN` payload: `TurnResponse.plan` (§6). `POST` payload: `PostContent` JSON.
- 매 사용자 입력·어시스턴트 응답을 즉시 저장 → 자동 임시 저장(FR-4.4). Home의 "올리다 만 글" 배너 + "이어서 쓰기" 목록은 `chat_session`에서 온다. SP1의 `PendingJob`은 발행 단계에서만 생성되고 세션에 연결된다.

## 6. 턴 프로토콜 (어시스턴트 응답 스키마)

모든 어시스턴트 턴은 아래 JSON(Gemini `responseSchema`)으로 온다. UI는 이 구조를 그대로 그린다.

```jsonc
{
  "say": "사진 보니까 원주 한우 다녀오신 날이네요! 이렇게 써 볼까요?",   // 말풍선 본문 (2~4문장)
  "plan": {                                                         // 없으면 null
    "titleCandidates": ["원주 한우 맛집, 가족과 다녀온 후기", "…", "…"],   // 2~3개
    "outline": [ { "heading": "도입 — 어떻게 가게 됐는지", "summary": "…", "photoRefs": ["img_001"] }, … ],
    "tone": "따뜻한 존댓말, 이모티콘 조금"
  },
  "question": "제목은 어떤 게 마음에 드세요?",                        // 한 턴에 질문 하나, 없으면 null
  "quickReplies": ["1번 제목으로", "더 짧게", "이대로 초안 써 줘"],   // 최대 4개
  "readyToDraft": false,                                             // 계획이 충분히 합의되면 true
  "post": null                                                       // 초안 요청 턴에서만 PostContent(SP1 스키마)
}
```
- 사용자가 "초안 작성"(칩 또는 자유 입력)을 보내면 `PromptBuilder`가 "이번 턴은 `post`를 채운다"는 지시를 추가하고, 응답의 `post`를 `PostContentJson`으로 검증 → 이미지 ref가 첨부 목록과 일치하는지 검사 → `PendingJob` 생성 → 사이드 패널 열기.
- 부분 수정: 패널이 열린 상태에서 사용자 메시지 → 프롬프트에 현재 `post` 전문 포함 + "수정된 전체 post를 다시 낸다" 지시 → 새 `post` → `PublishStateMachine`에 새 이벤트 `Reinject(content)`: `Reviewing`에서 `Injecting`으로 되돌아가 `setDocument` 재실행(이미지는 이미 업로드됨 → `uploaded` 맵 재사용, 새 사진이 추가됐으면 그 사진만 업로드).
- 응답이 스키마를 어기면(파싱 실패) 같은 키로 1회 재요청(온도 0), 그래도 실패면 "다시 말해 주세요" 시스템 메시지.

## 7. 도구 (function calling)

| 도구 | 시그니처 | 구현 | 제한 |
|---|---|---|---|
| `web_search` | `(query: string) → [{title, url, snippet}]` (최대 5) | `WebResearchTool`: 숨은 WebView가 **네이버 검색**(`https://search.naver.com/search.naver?where=view&query=…`)을 먼저 로드하고, 추출 결과가 0건이거나 실패하면 **구글**(`https://www.google.com/search?q=…`)로 폴백 → `research_extract.js`가 결과 카드에서 제목/URL/요약을 추출 | 턴당 2회, 8초 제한 |
| `open_page` | `(url: string) → {title, text}` (본문 최대 4,000자) | 같은 WebView로 로드 → 스크립트가 `article`/가장 큰 텍스트 블록을 골라 정리(광고·내비 제거) | 턴당 2회, 10초 제한, `http(s)`만 |
| `remember` | `(kind: STYLE|PREFERENCE|FACT|EXPRESSION, text: string) → {saved: true, id}` | `MemoryStore`에 **즉시 저장**(활성). 어시스턴트는 그 턴의 `say`에 "기억해 둘게요: …" 한 줄로 보고한다(상용 LLM 서비스의 메모리 방식). 관리 화면에서 편집·삭제 | 턴당 2회 |

- **도구 진행 표시(사용자 결정)**: 도구가 실행되는 동안 어시스턴트 말풍선 자리에 상태 줄을 보여 준다 — `web_search`: "네이버에서 '{query}' 정보를 찾고 있어요…", `open_page`: "'{title 또는 도메인}' 페이지를 읽고 있어요…", `remember`: "기억해 둘게요…". 상태 줄은 저장하지 않고 화면에만 표시하며, 응답이 오면 말풍선으로 교체된다. 도구가 연속되면 줄이 갱신된다.
- 검색은 **LLM이 필요하다고 판단할 때만** 호출된다(시스템 프롬프트에 "사실 확인이 필요한 정보 — 영업시간, 주소, 가격, 행사 날짜 — 만 검색" 규칙). 그라운딩 API는 쓰지 않으므로 검색 결과는 `functionResponse`로 되돌려 주고 본문에 출처를 억지로 넣지 않는다.
- 검색 툴은 실패해도 대화를 막지 않는다(빈 결과 반환 + 로그).
- 검색 HTML 구조 변경 위험: 추출 실패 시 빈 결과. 관리자 화면에서 "검색 도구 사용" 토글로 끌 수 있다.

## 8. 프롬프트 설계 (FR-11c)

`PromptBuilder`가 매 턴 조립하는 시스템 프롬프트의 섹션(순서 고정). 각 섹션의 기본 문안은 `assets/prompts/` 리소스 파일이며 **관리자 화면에서 편집 가능**(DataStore 오버라이드, 기본값 복원 가능). SP3의 프롬프트도 같은 방식으로 편집한다:

1. **역할**: "당신은 한국어 네이버 블로그 글쓰기 도우미. 40대 여성 블로거(사용자)의 목소리로 글을 쓴다. 대화는 짧고 다정하게, 질문은 한 번에 하나."
2. **독자와 목적**: 지인·같은 관심사의 이웃 블로거. 목적은 기록과 공유, 과장 광고 금지.
3. **스타일 프로필**(SP3 전까지는 메모리의 STYLE 항목으로 대체): 말투(존댓말/반말), 문단 길이, 이모티콘 빈도, 자주 쓰는 표현, 사진 배치 습관.
4. **개인화 메모리**: `MemoryStore.promptSummary()` — 활성 항목을 종류별로 최대 40개, 최근 사용순. 예: "PREFERENCE: 가격은 정확히 적는 걸 좋아함", "EXPRESSION: 글 끝에 '오늘도 감사한 하루였어요'".
5. **글 구조 규칙**:
   - 제목: 핵심 키워드를 앞에, 20~30자, 낚시 금지, 후보 3개는 서로 다른 관점(장소 중심/감정 중심/정보 중심).
   - 도입 2~3문장(왜 갔는지/무슨 날인지) → 본문은 사진 흐름을 따라 3~5개 소제목(fs28, 굵게) → 마무리(감상 + 한 줄 추천/팁).
   - 문단은 2~4문장, 사진마다 그 사진에 대한 설명 1~2문장이 바로 앞 또는 뒤에 온다(사진 내용은 vision으로 실제 확인한 것만 서술, 추측 금지).
   - 서식: 굵게는 문단당 최대 1회, 형광펜은 글 전체 2회 이하, 인용구는 한 줄 감상에 1회, 목록은 정보(메뉴·가격·주소)에만.
   - 사실 정보(주소·가격·영업시간)는 사용자가 말했거나 검색으로 확인한 것만. 모르면 쓰지 않거나 "확인이 필요해요"로 사용자에게 묻는다.
   - 길이: 기본 900~1,400자(사용자가 "짧게/길게" 하면 ±40%). SP3의 스타일 프로필이 사용자의 실제 글 길이를 측정하면 그 값이 기본값을 대체한다(`ModelPolicy.targetLength`, 관리자 화면에서 확인·수정 가능).
6. **대화 규칙**: 첫 응답은 반드시 `plan` 포함; 사용자가 고른 제목·수정 요청을 다음 계획에 반영; `readyToDraft`는 제목이 정해지고 개요에 이의가 없을 때만 true; 초안 턴에서는 `post`만 채우고 `say`는 한 문장.
7. **출력 스키마**: §6 JSON, `post`는 SP1 `PostContent` 스키마(문단 run 스타일, `Block.Image(ref)`, `Block.Quote`).
8. **자기 점검**(초안 턴 전용): "제출 전에 확인 — 모든 사진 ref가 정확히 한 번씩 쓰였는가, 소제목이 사진 흐름과 맞는가, 사실 추측이 없는가, 글자 수 범위인가." (모델이 내부적으로 검토하도록 지시; 별도 호출 없음)

품질 반복: 실제 사용자 글 3~5편을 few-shot으로 넣는 것은 SP3(스타일 프로필)에서 결정. SP2에서는 메모리 항목으로 대체.

## 9. 화면 (디자인 가이드 §8)

- **ChatScreen**: 가로 **3단** — 왼쪽 **대화 기록**(세션 목록: 제목·마지막 수정·상태 칩, 폭 280dp, 아이콘 바 72dp로 최소화 가능, "새 글" 버튼 상단 고정), 가운데 채팅, 오른쪽 `PublishPanel`(50%·최소 520dp, 초안 작성 시 슬라이드 인). 패널이 열리면 대화 기록은 자동으로 최소화된다. 세로 모드: 대화 기록은 왼쪽 드로어, 패널은 전체 화면 시트. 상단 얇은 바: 세션 제목(첫 제목 후보 또는 "새 글") + 패널 접기/펼치기.
- **Composer**: 첨부(사진 고르기 → PHOTOS 메시지로 추가, 기존 PhotoGrid 재사용), 텍스트, 마이크(탭 시작/정지, 인식 중 파형 대신 점 애니메이션), 보내기. 어시스턴트 응답 대기 중 입력 비활성 + "글을 구상하고 있어요".
- **PlanCard**: 제목 후보 3개(ListRow, 선택 시 사용자 메시지 "N번 제목으로" 자동 전송), 개요 목록(소제목 + 사진 썸네일 작은 칩), 톤 한 줄. 칩: `quickReplies`.
- **Home**: "새 글 쓰기" → 새 세션; "이어서 쓰기" 목록(세션 제목, 마지막 수정 시각); 키 없음 배너.
- **관리자**: PinGate → Settings 목록(API 키, 모델, **프롬프트 편집**, 메모리, 검색 도구 토글, 실패 로그, 네이버 로그아웃).
- **MemoryScreen**(사용자도 접근 가능 — 채팅 화면 상단 메뉴 "기억한 것들"): 상용 LLM 서비스의 메모리 화면처럼 항목을 문장 목록으로 보여 주고, 탭하면 그 자리에서 **글 편집하듯 수정**, 삭제(ConfirmSheet), 활성/비활성 스위치, "직접 추가" 입력창. 종류(STYLE/PREFERENCE/FACT/EXPRESSION)는 칩으로 표시만 한다.
- **PromptScreen**(관리자): §8의 각 섹션을 편집 가능한 텍스트로 표시(기본값은 `assets/prompts/*.md`, 수정본은 DataStore에 저장, "기본값으로 되돌리기" 버튼). SP3의 스타일 분석·진단 프롬프트도 같은 화면에서 편집한다.

## 10. 오류 처리

| 상황 | 처리 |
|---|---|
| 키 없음/전부 무효 | 글쓰기 차단 + 배너(§4.4) |
| 429 전부 소진 | 말풍선: "지금은 잠깐 쉬어야 해요. N분 뒤에 다시 시도할게요." + 재시도 버튼 |
| 네트워크 없음 | 말풍선: "인터넷이 연결되어 있지 않아요." 입력은 저장됨 |
| 스키마 위반 | 1회 재요청 → 실패 시 "다시 말해 주세요" |
| 이미지 ref 불일치 | 앱이 자동 보정(없는 ref 제거, 누락 사진은 끝에 추가) 후 진행, 로그 남김 |
| 검색 툴 실패 | 빈 결과, 대화 계속 |
| 발행 실패 | SP1 폴백 그대로 (패널 안에서 표시) |

## 11. 테스트 전략

- 순수 Kotlin: `ApiKeyParser`(다중 줄·쉼표·따옴표·중복·비키 토큰), `KeyRotator`(라운드로빈, 쿨다운, 모델 다운그레이드·원복, 전부 소진), `TurnResponse` 디코드(누락 필드·잘못된 post), `PromptBuilder`(섹션 순서, 메모리 상한, 초안 턴 지시), `PostContent` ref 보정.
- Robolectric: `ApiKeyStore` 암복호화 왕복(Keystore는 Robolectric에서 가짜 제공자 필요 → 인터페이스로 분리해 테스트에선 평문 구현), Room 스키마 마이그레이션(SP1 v1 → v2).
- `GeminiClient`: MockWebServer로 정상/429/함수호출 왕복 시나리오.
- `WebResearchTool`: JS 추출 스크립트는 저장된 검색 결과 HTML 픽스처를 WebView에 `loadDataWithBaseURL`로 넣어 에뮬레이터에서 수동 검증.
- 종단: 에뮬레이터에서 실제 키로 사진 2장 + 아이디어 → 계획 → 초안 → 발행.

## 12. 결정 필요 (가정값)

| 항목 | 가정 | 대안 |
|---|---|---|
| 검색 엔진 | **결정**: 네이버 검색 우선, 실패 시 구글 | — |
| 메모리 승인 방식 | **결정**: 자동 저장 + 채팅에서 보고, 관리 화면에서 편집·삭제 | — |
| 프롬프트 파일 위치 | **결정**: `assets/prompts/*.md` 기본값 + 관리자 화면에서 편집(SP3 프롬프트 포함) | — |
| 글 길이 기본 | **결정**: 900~1,400자, SP3 스타일 프로필이 측정값으로 대체 가능 | — |
| 모델 ID | `gemini-3.7-flash` / `gemini-3.5-flash-lite` (2026-08-28 문서 기준), 설정에서 변경 가능 | 고정 |
| 사진 프롬프트 해상도 | 긴 변 1024px | 768px(토큰 절약) |

## 13. SP1과의 접점

- `PublishPanel`/`PublishViewModel` 재사용. 추가: `PublishEvent.Reinject(content)` + `PublishEffect.Inject` 재실행 경로, `uploaded` 맵 유지.
- `TestComposeScreen` 제거, `Routes.TestCompose` → `Routes.Chat(sessionId)`.
- Room 버전 2 마이그레이션(테이블 추가만).
- `SettingsStore`에 PIN 해시·모델 설정·검색 툴 토글 추가.
