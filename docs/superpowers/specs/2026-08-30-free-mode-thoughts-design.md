# 자유 모드 + 생각 표시 설계 (SP3-2)

- 작성일: 2026-08-30
- 선행: `2026-08-29-advice-mode-design.md`(모드 구조), 스파이크(§8)
- 범위: (1) 범용 비서 "자유 모드"(기억 반영·제안 포함), (2) 세 모드 공통 "생각 요약" 표시. thinking 단계 설정 화면은 만들지 않는다.

## 1. 목표

- 같은 채팅 화면에서 "자유"를 고르면 블로그와 무관한 범용 한국어 비서로 쓴다. 사진을 붙여 물어볼 수 있고, 웹 검색을 쓸 수 있으며, 기억(메모리)을 참고하고 새로 기억할 만한 내용은 **모델이 먼저 제안**한다.
- 모델이 답하기 전에 생각한 요약을 연하고 작게 보여 주고, 답이 오기 시작하면 접는다. 세 모드 모두.

## 2. 데이터·모드

- `SessionMode`에 `FREE` 추가. DB 변경 없음(`chat_session.mode`는 문자열).
- 허용 목록(모두 allow-condition으로 쓴다):
  - 사진 첨부·사진판: `mode in setOf(WRITE, FREE)`
  - 사진 묶기·초안 게이트·계획 패널·"이대로 초안 써 줘"·품질 게이트·발행 훅: `mode == WRITE`
  - 오른쪽 글 보기 패널·첫 턴 글 목록: `mode == ADVICE`
- 자유 세션 제목 = 첫 사용자 메시지 24자(조언과 같음). `status`는 항상 `DRAFTING`.

## 3. 프롬프트·스키마·도구 (자유 모드)

- `PromptGroup.FREE("자유")`, 섹션 2개(설정 화면에서 편집·되돌리기):
  - `FREE_ROLE` "자유·역할" `f1_free_role.md`: 한국어 범용 비서. 짧고 정확하게, 모르면 모른다고, 마크다운(제목·목록·굵게) 허용, 블로그·글쓰기 페르소나 없음. 사용자는 컴퓨터에 익숙하지 않으니 기술 용어를 풀어서. 최신 정보·사실 확인은 `web_search`로.
  - `FREE_MEMORY` "자유·기억 제안" `f2_free_memory.md`: 대화 중 다음 글쓰기에 도움이 될 취향·습관·자주 쓰는 표현·사실이 나오면 답 **맨 끝에 한 줄** `이 내용 기억할까요? — "…"`로 제안한다(턴당 최대 1개; 같은 세션에서 거절했거나 이미 기억 목록에 있는 건 다시 묻지 않음). 사용자가 좋다고 하면 그때 `remember`를 부르고 `기억해 둘게요: …`로 알린다. 묻지 않고 저장하지 않는다. 사용자가 "이거 기억해 줘"라고 직접 말하면 바로 저장한다.
- 조립 순서: `FREE_ROLE` → `MEMORY`(`{{memory}}`, 기존 04) → `FREE_MEMORY`. STYLE·글쓰기·조언 섹션은 넣지 않는다.
- 응답 스키마 `{ say }`. 도구: `web_search`, `open_page`, `remember`(기존 한도 2/2/2). `list_my_posts`/`read_my_post`는 주지 않는다.
- 컨텍스트: 기억 항목(`activeItems`)은 주입, `style`은 null. 첨부 사진은 글쓰기와 같은 배관(세션 첨부 → 첫 user 파트 inlineData, `PHOTOS` 메시지). 계획·초안·사실 확인 지시는 붙이지 않는다(히스토리는 TEXT + "(사진 N장 첨부)").
- thinkingLevel: 자유 턴 `high`(조언과 동일). 글쓰기는 기존 low/high 그대로.

## 4. 생각 표시 (세 모드 공통)

### 4.1 엔진
- 모든 요청의 `thinkingConfig`에 `includeThoughts: true`(`GThinkingConfig(thinkingLevel, includeThoughts = true)`). 400에서 thinkingConfig를 빼는 기존 폴백은 그대로(그때는 생각도 안 온다).
- 스트림에서 `part.thought == true`인 텍스트는 답 텍스트와 **분리**해 누적한다. `GPart`에 `thought: Boolean? = null` 추가; `GResponse.text`는 thought 파트를 제외한다(기존 JSON 파싱이 생각 문장으로 오염되지 않게), `GResponse.thoughtText`를 새로 둔다.
- `TurnListener.onPartialThought(text: String)` — 지금까지의 생각 전체(교체 방식, `onPartialSay`와 같은 규약; 새 스트림마다 빈 문자열로 초기화). 도구 라운드가 여러 번이면 라운드마다 생각이 이어서 쌓인다(라운드 사이 구분 줄바꿈).
- `TurnResult.Success`에 `thought: String?` 추가(빈 문자열이면 null). `TurnResponse`는 손대지 않는다(모델 출력 스키마가 아니므로).

### 4.2 저장
- 어시스턴트 TEXT payload를 `{"text": …, "thought": …}`로 저장(`ChatPayloads.assistantText(text, thought)`, `readThought(payload): String?`). `readText`는 그대로. 생각이 없으면 `thought` 키를 넣지 않는다.
- 모델 히스토리(`buildContents`/`adviceContents`)에는 생각을 싣지 않는다.

### 4.3 화면
- `ChatUiState.streamingThought: String?`, `thoughtCollapsed: Boolean`(스트리밍용). 턴 시작 때 null/false. `onPartialThought`가 오면 갱신, **답 파트가 처음 오는 순간(`onPartialSay`에 빈 문자열이 아닌 값이 처음 들어올 때) `thoughtCollapsed = true`**. 턴이 끝나면 둘 다 초기화(저장된 메시지가 이어받는다).
- `ThoughtBlock(text, expanded, onToggle)` 컴포저블: 어시스턴트 말풍선 **위**에, `caption` 크기·`textTertiary` 색·말풍선 배경 없음. 접힌 상태는 첫 줄만(`maxLines = 1`, Ellipsis), 펼치면 전체. 터치 영역 56dp. 스트리밍 중엔 `!thoughtCollapsed`이면 펼침, 저장된 메시지는 기본 접힘(메시지별 `remember` 상태).
- 자유 모드 어시스턴트 말풍선은 `MarkdownLite`로 렌더(`MessageBubble(markdown = true)`); 글쓰기·조언은 그대로 평문.

## 5. UI (자유 모드)
- 모드 칩 라벨: `✍️ 글쓰기` / `💬 조언` / `🤖 자유`. 드롭다운 항목 3개.
- 히어로 제목 "무엇이든 물어보세요", 플레이스홀더 "궁금한 것을 물어보세요", 생각 중 플레이스홀더 "생각하고 있어요".
- 사진 버튼 보임(`showAttach = mode in {WRITE, FREE}`), 사진판은 묶기 버튼 없이(묶기는 WRITE만). 오른쪽 패널·"이대로 초안 써 줘"·로그인 안내 없음(자유는 로그인 불필요 — `blogId` 없어도 보낼 수 있다).
- 세션 목록 칩 "자유", 둘째 줄 "자유".
- 프롬프트 화면 그룹 "자유" 추가(기존 그룹 로직 그대로).

## 5.1 설정 — "업데이트 확인" 버튼 (같이 개발, 사용자 요청 2026-08-30)
- 설정 화면 "실패 로그" 아래 `업데이트 확인` 줄(부제 "지금 버전 vX"). 누르면 `UpdateChecker.checkForUpdate()`를 바로 호출(10분 간격 무시).
- 결과를 그 자리에 배너로: 확인 중 / 새 버전(태그) — 받으러 가기(릴리스 페이지) / 최신 버전이에요 (vX) / 확인하지 못했어요.
- 새 버전이면 닫아 둔 배너 태그를 풀고 마지막 확인 시각을 0으로 — 채팅으로 돌아가면 배너가 다시 뜬다.

## 6. 오류 처리
- thought 파트가 하나도 없으면 아무것도 표시하지 않는다(minimal 모델·폴백 요청).
- 마크다운 렌더가 실패할 만한 입력은 없다(`MarkdownLite`는 문단·목록·굵게만 다루고 나머지는 평문).

## 7. 테스트
- `GeminiModels/Client`: thought 파트가 `text`에서 빠지고 `thoughtText`에 모이는지.
- `TurnSchemasTest`: FREE 스키마 `{say}`·도구 3개. `PromptBuilderTest`: FREE 조립 순서·글쓰기/조언 섹션 미포함·`{{memory}}` 주입.
- `ConversationEngineTest`: 요청에 `includeThoughts: true`; SSE에 thought 파트 → `onPartialThought` 누적·`onPartialSay`에 안 섞임·`Success.thought`; FREE 턴 contents에 계획/초안/사실 확인 지시 없음, 사진 inlineData는 있음, thinkingLevel high.
- `ChatPayloadsTest`: `assistantText`/`readThought` 라운드트립, `readText` 하위 호환.
- `ChatViewModelTest`: FREE 세션 생성·제목·사진 첨부 허용·묶기/초안 거부·로그인 없이도 전송; `onPartialThought`→`streamingThought`, 첫 `onPartialSay`에서 `thoughtCollapsed = true`; 성공 시 payload에 thought 저장; 턴 종료 시 스트리밍 상태 초기화.
- 화면은 컴파일만.

## 8. 스파이크 결과 (2026-08-30, 사용자 임시 키 — 파일 삭제함)
- `gemini-3.6-flash`·`3.5-flash-lite` 모두 `thinkingLevel` minimal/low/medium/high 200. minimal은 thoughts 토큰 0.
- `includeThoughts: true` SSE: `thought: true` 파트들이 먼저(3~6개 청크), 이어서 답 파트, 마지막 파트에 `thoughtSignature`. 생각 요약은 한국어 질문에도 **영어**(굵은 소제목 + 문단, 600~2,700자). 사용자 결정: 영어 그대로 표시, 라벨 없음.

## 9. 범위 밖
thinking 단계 설정 화면, 생각 번역, 글쓰기·조언 모드의 기억 제안(자유 모드에서만), 자유 모드 오른쪽 패널, 메시지 단위 사진 첨부.
