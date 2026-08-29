# 조언 모드 설계 (SP3-1)

- 작성일: 2026-08-29
- 근거: `docs/advice-mode-research.md` (LLM 블로그 조언의 학술 근거·사례·데이터 확보 실측)
- 범위: **글 개선 조언만**. 글 본문과 공개 지표(제목·날짜·댓글/공감 수)만 읽는다. 크리에이터 어드바이저 통계, 운영 조언(주제·발행 빈도), 메모리 제안 흐름, 조언→글쓰기 전환은 범위 밖.

## 1. 목표

같은 채팅 화면에서 사용자가 "조언 모드"를 골라 대화를 시작하면, 앱이 사용자의 최근 글을 읽고 모델이 **그 글의 실제 문장을 근거로** 구조·구체성·경험 신호·제목에 대한 개선 조언을 한다. 조언은 대화로 이어지며(“칼국수 글은 어때?”, “다른 글도 봐 줘”), 오른쪽 패널에 지금 이야기 중인 글이 보인다.

## 2. 접근

기존 `ConversationEngine`(스트리밍·키/모델 로테이션·도구 루프)을 그대로 쓰고, 세션의 **모드**에 따라 프롬프트 섹션 세트와 도구·응답 스키마만 바꿔 끼운다. 새로 추가되는 것은 블로그를 읽는 `BlogReader`와 조언용 프롬프트 3개, 화면의 모드 선택 칩과 글 보기 패널 연결이다.

기능 허용 조건은 언제나 **`mode == WRITE`일 때만 켠다**(초안 게이트, 계획 패널, "이대로 초안 써 줘" 버튼, 사진 첨부·묶기, 품질 게이트, 발행 훅, 메모리 추출). 모드가 늘어나도 조건이 불어나지 않게 하기 위함이다.

## 3. 데이터·모드

- `ChatSessionEntity.mode: String`(`WRITE` | `ADVICE`, 기본 `WRITE`). Room 버전 2 → 3, `ALTER TABLE … ADD COLUMN mode TEXT NOT NULL DEFAULT 'WRITE'`.
- `ChatSession.mode: SessionMode`. `createSession(mode)`. 세션은 만들 때만 모드를 받고 이후 고정.
- 조언 세션의 `status`는 항상 `DRAFTING`(발행 흐름 없음).
- 메시지 kind 추가(둘 다 모델 히스토리에는 싣지 않고 화면·복원에만 쓴다 — `PHOTO_GROUPS`와 같은 취급):
  - `BLOG_POSTS` — 앱이 읽은 최근 글 목록 스냅샷 `{posts:[{logNo,title,date,comments,likes}]}`. 화면엔 얇은 한 줄 "최근 글 N개를 읽었어요".
  - `POST_VIEW` — 모델이 본문을 읽은 글 `{logNo,title}`. 화면엔 "'제목' 글을 읽었어요". 재열기 때 마지막 `POST_VIEW`로 오른쪽 패널을 복원.
- 읽기 빈도 원칙(약관 리스크 = 계정 제한): **사용자 메시지 1회당** 목록 1회, 본문 최대 3편. 같은 글은 메모리 캐시(최근 20편, **10분 TTL** — 고친 글을 다시 볼 수 있게)로 다시 읽지 않는다. 백그라운드 갱신 없음.

## 4. 블로그 읽기 — `blog/BlogReader`

```kotlin
data class PostSummary(val logNo: String, val title: String, val date: String, val comments: Int, val likes: Int)
data class PostText(val logNo: String, val title: String, val date: String, val paragraphs: List<String>, val imageCount: Int)
interface BlogReader {
    suspend fun listPosts(blogId: String, count: Int = 30): List<PostSummary>?   // 실패 null
    suspend fun readPost(blogId: String, logNo: String): PostText?               // 실패 null
}
```

- `listPosts`: OkHttp GET `https://m.blog.naver.com/api/blogs/{blogId}/post-list?categoryNo=0&itemCount={count}&page=1`, 헤더 `Referer: https://m.blog.naver.com/{blogId}`(없으면 403), `CookieManager`의 네이버 쿠키 동봉(이웃공개 글 포함). 파싱: `result.items[]`의 `logNo`(정수), `titleWithInspectMessage`, `addDate`(epoch ms), `commentCnt`, `sympathyCnt`; `briefContents`(약 500자 요약)와 `thumbnailCount`도 함께 담아 목록 표에 쓴다. `isSuccess=false`면 null.
- `readPost`: 모바일 `PostView.naver`는 **서버 렌더링**이라(스파이크 §9) WebView 없이 OkHttp GET + **Jsoup**(새 의존성 `org.jsoup:jsoup`)으로 파싱한다. 선택자: 제목 `.se-title-text`, 본문 `div.se-main-container > div.se-component` 순서대로 — `se-text`/`se-quotation`은 `.se-text-paragraph` 텍스트(빈 문단 제거, 인용은 `> ` 접두), `se-table`은 행을 `항목: 값` 줄로, `se-image`/`se-imageGroup`/`se-imageStrip`은 `img` 수만 세어 `[사진 N장]` 줄로, `se-video`는 `[동영상]`, 그 밖(`se-placesMap`, `se-horizontalLine` 등)은 무시. 날짜는 페이지가 상대 시각("3시간 전")이라 쓰지 않고 목록의 `addDate`를 쓴다. 문단 합계 6,000자 상한(넘으면 자르고 `(이하 생략)`).
- 두 함수 모두 예외를 삼키고 `null`을 돌려주며 `Log.w("BlogReader", …)`만 남긴다. 히든 WebView는 쓰지 않는다(검색 도구와 자원 경합 없음).
- 모바일 URL 헬퍼 `postUrl(blogId, logNo)` — 오른쪽 패널과 공유.

## 5. 프롬프트·스키마

### 5.1 섹션
`PromptSection`에 조언 섹션 3개를 추가한다(파일은 `assets/prompts/`, 기존과 같이 관리자 화면에서 편집·되돌리기).

| 섹션 | 파일 | 내용 |
|---|---|---|
| `ADVICE_ROLE` "조언·역할" | `a1_advice_role.md` | 사용자의 블로그를 함께 읽는 편집자. 짧고 다정하게, 기술 용어 없이. 조언은 사용자의 글을 더 낫게 하는 것이지 대신 쓰는 것이 아님. |
| `ADVICE_GUARDS` "조언·판단 규칙" | `a2_advice_guards.md` | 리서치 §4: 비판 우선·근거는 **그 글의 실제 문장 인용**; 사용자가 반박해도 근거 없이 입장을 바꾸지 않음; 사용자 문체는 base로 유지(관용구·말투 교체 제안 금지); 대량 발행·같은 주제 변주 추천 금지; "이렇게 하면 조회수/순위가 오른다"류 인과 약속 금지, 제목 A/B 승자 예측 금지; 경험 신호 체크리스트(1인칭 경험, 직접 찍은 사진, 구체 수치·가격·시간); 기준 구조는 글쓰기 8단(05_structure의 흐름 문장을 그대로 재사용); 검색 노출 얘기는 제목과 첫 요약 문단에 한정. |
| `ADVICE_OUTPUT` "조언·출력 형식" | `a3_advice_output.md` | 글 하나 → "잘한 점 1~2개 / 고칠 점 최대 3개(원문 인용 → 고친 예시 한 줄)". 여러 글 → "공통 경향 최대 3개 + 다음 글에서 해 볼 것 1개". 800자 안팎, 마크다운 없이 줄바꿈만. 모르면 글을 읽고(`read_my_post`) 답한다. 어떤 글인지 불명확하면 목록에서 후보 2~3개를 제목으로 되묻는다. |

조언 시스템 프롬프트 조립 순서: `ADVICE_ROLE` → `STYLE`(`{{style}}`) → `MEMORY`(`{{memory}}`) → `ADVICE_GUARDS` → `ADVICE_OUTPUT` → `[최근 글 목록]`(§5.3). 글쓰기 섹션(01, 02, 05~08)은 넣지 않는다. `PromptBuilder.system(...)`에 `mode` 인자를 더하고 `{{minLen}}/{{maxLen}}` 치환은 그대로.

### 5.2 응답·도구
- 조언 응답 스키마: `{ "say": string }`만. `TurnSchemas.responseSchema(mode)`, `TurnSchemas.tools(mode)`.
- 도구(조언 모드):
  - `list_my_posts()` — 최근 글 30개. 턴당 1회.
  - `read_my_post(logNo)` — 본문·사진 수·날짜. 턴당 3회, 같은 logNo는 캐시.
  - 글쓰기 도구(`web_search`, `open_page`, `remember`)는 조언 모드에 주지 않는다.
- `DefaultToolExecutor`는 한도 표에 조언 도구를 더한다(모델이 보는 도구 목록은 `TurnSchemas.functionDeclarations(mode)`가 정한다). `read_my_post` 성공 시 엔진이 `TurnListener.onPostRead(logNo, title)`을 부른다(오른쪽 패널·`POST_VIEW` 저장용). 리스너는 턴마다 자기 세션 id를 안고 만들어져 늦은 콜백이 다른 대화에 붙지 않는다.
- thinkingLevel: 조언 턴은 `high`(초안 턴과 동일). 400이면 기존과 같이 thinkingConfig를 빼고 재시도.

### 5.3 첫 턴
조언 세션의 첫 사용자 메시지 전송 시 `ChatViewModel`이 `BlogReader.listPosts`를 먼저 호출한다(진행 문구 "최근 글을 읽고 있어요"). 성공하면 `BLOG_POSTS` 메시지로 저장하고 시스템 프롬프트 끝에 표(`logNo | 제목 | 날짜 | 댓글 | 공감`)로 넣는다. 실패하면 SYSTEM 한 줄 "글 목록을 읽지 못했어요. 네이버 로그인 상태를 확인해 주세요."를 붙이고 프롬프트엔 "(목록 없음 — 사용자가 글을 지목하면 read_my_post로 읽는다)"로 진행한다. 이후 턴은 저장된 `BLOG_POSTS`를 쓴다(모델이 `list_my_posts`를 부르면 그 턴의 도구 결과로만 쓰고 저장본은 갱신하지 않는다). 목록 읽기는 사용자 메시지를 저장한 뒤에 한다 — 실패 안내가 사용자 말 뒤에 오도록.

## 6. UI

- **모드 선택**: 새 세션(히어로) 컴포저 하단 줄 맨 왼쪽에 드롭다운 칩 `✍️ 글쓰기 ▾` / `💬 조언`. 조언을 고르면 히어로 제목이 "블로그를 함께 살펴볼까요?"로 바뀌고 사진 버튼이 사라진다(마이크 유지). 첫 메시지를 보내는 순간 그 모드로 세션을 만든다. 세션이 생긴 뒤 칩은 눌리지 않는 라벨. 세션 목록의 조언 세션엔 작은 "조언" 칩.
- **미로그인**: `blogId`가 없으면 조언 모드에서 보내기를 막고 기존 로그인 안내(`LoginNudge`)를 "조언은 네이버 로그인 후에 받을 수 있어요"로 보여 준다.
- **오른쪽 패널**: 조언 모드에서는 `PostViewPanel` — 기존 `PublishedPostPanel`(모바일 WebView + "브라우저에서 열기")을 URL만 바꿔 재사용. 모델이 `read_my_post`를 부르면 자동으로 열리고(3:7·5:5 비율 규칙 동일) 제목을 패널 헤더에 표시. 재열기 때 마지막 `POST_VIEW`로 복원. `PlanPanel`·에디터·"이대로 초안 써 줘"는 WRITE에서만.
- **프롬프트 화면**: 글쓰기/조언 두 그룹으로 나눠 표시. 섹션 제목에 오버라이드가 있으면 `*` 접미(예: `역할 *`) — 앱을 업데이트해도 `*`가 남아 있으면 기본값 변경이 반영되지 않는 섹션임을 알 수 있다. 기존 "수정됨" 배지·"기본값으로 되돌리기"(확인 시트)는 그대로.
- 문구는 "~해요"체, 터치 56dp.

## 7. 오류 처리

- 목록/본문 읽기 실패 → null → 도구 결과에 `{"error":"읽지 못했어요"}`로 모델에 알리고 진행. 사용자에겐 도구 진행 문구로만 보임(첫 턴 목록 실패만 SYSTEM 한 줄).
- 모델 오류(키·한도·503)는 기존 `TurnResult.Failure` 처리 그대로.
- 조언 세션에서 `panelJobId`, `draftGate`, 발행 콜백이 들어오는 일은 없어야 하며(호출 자체가 WRITE 조건 뒤에 있음), 방어 코드는 두지 않는다.

## 8. 테스트

- `BlogReaderTest`: 스파이크 픽스처(가명화)로 목록 JSON 파싱, PostView 추출 JS 결과 파싱, 6,000자 컷, 예외 → null.
- `PromptBuilderTest`: 조언 조립 순서, 목록 표 삽입, "목록 없음" 문구, 글쓰기 섹션 미포함.
- `TurnSchemasTest`: 모드별 스키마·도구 목록.
- `DefaultToolExecutorTest`: 턴당 한도(목록 1, 본문 3), 캐시, `onPostRead` 콜백.
- `ConversationEngineTest`: 조언 턴이 `say`만 낸다, `read_my_post` 라운드 처리.
- `ChatViewModelTest`: 조언 세션 생성(모드 고정), 첫 턴 목록 읽기 성공/실패, `POST_VIEW` 저장·복원, 미로그인 차단, WRITE 전용 상태(`draftGate`·`plan`·첨부)가 조언 세션에서 안 생김.
- `MigrationTest` 2→3.
- 화면(`ChatScreen`, `PromptsScreen`)은 컴파일만.

## 9. 스파이크 결과 (2026-08-29, 실제 공개 블로그로 확인)

픽스처(전부 가상 값·가상 문장)는 `app/src/test/resources/blog/`: `post-list.json`(실제 키 구조 그대로, 3건), `post-list-error.json`(Referer 없을 때의 403 본문), `post-view.html`(모바일 PostView의 클래스 구조를 축소 재현).

1. **post-list**: Referer 없이 호출 → 403 `{"isSuccess":false,"error":{…}}`. Referer 있으면 200, 30건 172 KB, 0.3 s. `result.{page,categoryNo,categoryName,totalCount,items[]}`. 항목 키: `logNo`(int), `titleWithInspectMessage`, `commentCnt`, `sympathyCnt`, `briefContents`(≈500자), `addDate`(epoch ms), `categoryName`, `thumbnailCount`, `readCount`(null — 조회수는 안 옴), `smartEditorVersion`.
2. **PostView(모바일)**: 로그인 없이 200, 159 KB, 0.4 s, **서버 렌더링**(`div.se-main-container` 1개, 최상위 `div.se-component` 44개: `se-text`, `se-image`, `se-imageGroup se-l-collage`, `se-imageStrip`, `se-video`, `se-quotation se-l-quotation_line|corner`, `se-table`, `se-placesMap`, `se-horizontalLine`). 문단 `p.se-text-paragraph > span`, 인용 `.se-quote`, 이미지 `img.se-image-resource[data-lazy-src]`, 제목 `.se-title-text`, 날짜 `p.blog_date`는 상대 시각. → 히든 WebView 대신 OkHttp + Jsoup으로 결정(§4).
3. 소요 시간: 목록 0.3 s + 본문 0.4 s(curl 기준). 히든 WebView였다면 이미지 로딩까지 수 초.

## 10. 범위 밖 (다음 SP)

크리에이터 어드바이저 통계(HMAC 서명, 별도 스파이크), 운영 조언, 조언 결과를 메모리/스타일에 병합(FR-9.3), 조언 세션에서 글쓰기로 이어 가기, 관리자 알림(FR-1.4).
