# 서브 프로젝트 1 — 발행 파이프라인 설계

- 일자: 2026-08-28
- 근거: `요구사항.md` v0.1, `spike/findings.md` (FR-7 스파이크), `docs/design-guide.md`
- 범위: FR-1.1~1.3, FR-7 (스파이크 결과로 개정), FR-8, FR-10.1, FR-10.3, 프로젝트 골격, GitHub Releases 배포
- 범위 밖 (SP2/SP3): Gemini 생성, API 키 관리, PIN, 스타일 프로필, 진단, 초안, 음성 입력, FR-1.4 본격 구현

## 1. 목표

손으로 쓴 테스트 글(제목 + 문단 + 사진)을 태블릿에서 **네이버 스마트에디터에 자동 입력하고, 사용자가 발행 버튼을 눌러 발행한 뒤, 이력에 기록**되는 흐름을 제품 수준으로 완성한다. SP2에서 Gemini가 만든 글이 이 파이프라인에 그대로 들어간다.

성공 기준:
1. 에뮬레이터/태블릿에서 로그인 → 테스트 글 작성 → 자동 입력 → 발행 → 이력 표시가 끝까지 동작한다.
2. 로그인이 풀린 상태에서 발행을 시도하면 재로그인 후 같은 글로 자동 재개된다.
3. 에디터 준비/업로드/주입 중 제한 시간 초과 시 폴백 화면(클립보드 복사 + 블로그 앱 열기)이 뜨고 실패 로그가 남는다.
4. `DocumentModelConverter`, URL 파서, 상태 기계는 단위 테스트로 잠긴다.
5. `git tag v0.1.0` 푸시로 서명된 APK가 GitHub Release에 올라간다.

## 2. 기술 스택 (확정)

| 영역 | 선택 |
|---|---|
| 빌드 | AGP 9.3.2 (빌트인 Kotlin; KGP 2.4.10을 클래스패스에 올려 사용, 실패 시 AGP 기본 2.2.10), Gradle 9.7.1, JDK 17, version catalog |
| SDK | compileSdk 37, targetSdk 37, **minSdk 33** (태블릿 전용) |
| UI | Jetpack Compose (BOM 2026.08.00), Material 3 1.4.0 위에 자체 토큰/컴포넌트(디자인 가이드), Navigation Compose 2.10.0 (type-safe routes) |
| DI | Hilt 2.60.1 + KSP 2.3.11, hilt-navigation-compose 1.4.0 |
| 데이터 | Room 2.8.4 (KSP), DataStore Preferences 1.2.1, kotlinx-serialization-json 1.11.0 |
| 이미지 | Photo Picker(`PickMultipleVisualMedia`), ExifInterface 1.4.2, BitmapFactory 샘플링 리사이즈, Coil 3.6.0(썸네일) |
| WebView | `android.webkit` + androidx.webkit 1.17.0 |
| 테스트 | JUnit4, kotlinx-coroutines-test, Turbine 1.2.1, Robolectric 4.16.1 |
| 배포 | GitHub Actions → 서명 APK → GitHub Release |

applicationId `com.csh.blogwriter`, 앱 이름 "블로그 도우미". 패키지 루트 `com.csh.blogwriter`.

## 3. 아키텍처

단일 모듈, 기능별 패키지. 계층은 셋이고 의존 방향은 `ui → domain ← data/publish/session`.

```
com.csh.blogwriter
├─ App.kt, MainActivity.kt            Hilt 진입점, configChanges로 회전 시 재생성 방지
├─ ui/
│  ├─ theme/        AppColors, AppTypography, AppSpacing, AppTheme      (디자인 가이드 §2~4)
│  ├─ components/   TopBar, BottomCta, WeakButton, ListRow, ProgressScreen, ResultScreen,
│  │                ConfirmSheet, InlineBanner, AppTextField, PhotoGrid  (디자인 가이드 §5)
│  ├─ navigation/   AppNavHost, Routes (type-safe)
│  ├─ home/         HomeScreen
│  ├─ login/        LoginScreen + LoginViewModel
│  ├─ compose/      TestComposeScreen + ViewModel      (SP1 임시: 제목/문단/사진 입력)
│  ├─ publish/      PublishScreen + PublishViewModel   (진행 오버레이 + WebView + 결과)
│  ├─ fallback/     FallbackScreen
│  ├─ history/      HistoryScreen + ViewModel
│  └─ admin/        FailureLogScreen                    (PIN은 SP2)
├─ domain/
│  ├─ model/        PostContent, Block, Run, FontSize, PublishJob, PublishStage, PublishFailure
│  └─ PublishStateMachine.kt         순수 Kotlin 상태 전이 (테스트 대상)
├─ publish/
│  ├─ NaverEditorWebView.kt          WebView 생성/설정, 클라이언트, JS 브리지, 로컬 이미지 인터셉트
│  ├─ EditorBridge.kt                Kotlin ↔ JS 호출 래퍼 (waitReady / dismissPopups / uploadImages / setDocument)
│  ├─ DocumentModelConverter.kt      PostContent + 업로드 결과 → documentModel JSON
│  ├─ ImagePreparer.kt               리사이즈/EXIF 회전/파일명 정규화 → cache 파일
│  ├─ PublishUrlParser.kt            발행 URL → logNo, 세션 만료 URL 판정
│  └─ assets/editor_bridge.js        에디터 내부 API 호출 스크립트
├─ session/
│  ├─ NaverSession.kt                로그인 상태, blogId 저장/조회
│  └─ BlogIdResolver.kt              MyBlog.naver 리다이렉트에서 blogId 추출
└─ data/
   ├─ db/           AppDatabase, PublishHistoryEntity/Dao, FailureLogEntity/Dao, PendingJobEntity/Dao
   ├─ prefs/        SettingsStore (DataStore): blogId, lastLoginAt
   └─ repo/         HistoryRepository, FailureLogRepository, PendingJobRepository
```

## 4. 도메인 모델

```kotlin
@Serializable data class PostContent(val title: String, val blocks: List<Block>)

@Serializable sealed interface Block {
    @Serializable data class Paragraph(val runs: List<Run>, val align: Align = Align.LEFT, val list: ListType? = null) : Block
    @Serializable data class Image(val ref: String) : Block          // ref = "img_001" (파일명 규칙, ASCII)
    @Serializable data class Quote(val text: String, val source: String? = null) : Block
}
@Serializable data class Run(val text: String, val bold: Boolean = false, val color: String? = null,
                             val background: String? = null, val size: FontSize = FontSize.BODY)
enum class FontSize(val code: String) { BODY("fs19"), TITLE("fs28") }   // 스파이크에서 검증된 코드만. 다른 크기는 에디터에서 확인 후 추가
enum class Align { LEFT, CENTER, RIGHT }
enum class ListType { BULLET, DECIMAL }

data class PublishJob(val id: String, val content: PostContent, val images: List<PreparedImage>)
data class PreparedImage(val ref: String, val file: File, val width: Int, val height: Int)
```

SP2의 LLM 출력은 이 `PostContent`(JSON)로 직접 받는다. HTML은 어디에도 등장하지 않는다.

## 5. 발행 흐름 (상태 기계)

```
Idle
 → PreparingImages(done/total)           ImagePreparer: 긴 변 1600px, JPEG 85, EXIF 회전 적용, img_001.jpg…
 → LoadingEditor                          WebView.loadUrl("https://blog.naver.com/{blogId}?Redirect=Write")
      ├─ URL이 nid.naver.com/* 로 가면 → SessionExpired(job 저장)
      └─ pageFinished 후 500ms 폴링, 30s 제한: SmartEditor._editors.blogpc001 존재
 → DismissingPopups                       "작성 중인 글이 있습니다" → 취소, 도움말 → 닫기 (최대 3회 시도)
 → UploadingImages(done/total)            JS가 https://blog.naver.com/__app__/img_001.jpg 를 fetch
                                          → shouldInterceptRequest 가 cache 파일을 image/jpeg 로 응답
                                          → File → createSourceList → uploadImagesFromFiles (await 2회)
                                          → 이미지당 60s 제한, 결과(url,width,height,fileSize,fileName)를 브리지로 회신
 → Injecting                              Kotlin 이 DocumentModelConverter 로 JSON 생성 → JS setDocumentData, 15s 제한
                                          → getDocumentData 로 컴포넌트 수 검증(제목1 + 블록 수)
 → Reviewing                              오버레이 제거, WebView 노출. 사용자가 에디터의 "발행" 탭
      └─ URL 이 PostView.naver?…logNo=N…isAfterWrite=true 로 바뀌면
 → Published(logNo, url)                  HistoryRepository 저장, PendingJob 삭제, ResultScreen(성공)
Failed(stage, message)                    어느 단계든 제한 시간/JS 오류/네트워크 오류 → FailureLog 저장 → FallbackScreen
```

- `PublishStateMachine`은 순수 Kotlin: `(state, event) -> (state, effects)`. 이벤트: `ImagesPrepared`, `PageLoaded(url)`, `EditorReady`, `PopupsDismissed`, `ImageUploaded(ref, result)`, `Injected`, `UrlChanged(url)`, `Timeout(stage)`, `JsError(stage, msg)`. 효과: `LoadUrl`, `RunJs(step)`, `SaveHistory`, `SavePending`, `LogFailure`. ViewModel이 효과를 실행한다.
- 재개(FR-1.3): `SessionExpired`에서 `PendingJob(id, PostContent JSON, 이미지 파일 경로들)`을 Room에 저장하고 Login으로 이동. 로그인 성공 시 PendingJob이 있으면 PublishScreen으로 자동 진입해 `PreparingImages`부터 다시 시작 (준비된 파일이 있으면 재사용).
- Reviewing 상태에서 사용자가 뒤로가기를 누르면 ConfirmSheet("작성 중인 글을 두고 나갈까요?") → 나가면 PendingJob 유지(이력 화면에서 "이어서 발행" 가능). 이 이어하기 진입점은 Home의 InlineBanner("올리다 만 글이 있어요 →")로 제공한다.

## 6. WebView 세부

- 설정: 데스크톱 Chrome UA, JS/DOM storage on, `setAcceptThirdPartyCookies(true)`, `onPageFinished`에서 `CookieManager.flush()`, 디버그 빌드에서만 `setWebContentsDebuggingEnabled(true)`.
- 로컬 이미지 제공: `shouldInterceptRequest`가 `https://blog.naver.com/__app__/{ref}.jpg` 요청만 가로채 `WebResourceResponse("image/jpeg", null, FileInputStream)`을 반환. 같은 origin이므로 CORS 없음. 그 외 요청은 통과.
- JS 브리지 (`AndroidBridge`): `onReady()`, `onPopupsDismissed(n)`, `onImageUploaded(ref, resultJson)`, `onImageFailed(ref, err)`, `onInjected(componentCount)`, `onError(step, message)`. 모든 콜백은 메인 스레드로 넘겨 ViewModel 이벤트로 변환.
- `editor_bridge.js`는 페이지 로드 후 한 번 주입되어 `window.__app` 네임스페이스에 함수를 정의한다: `isReady()`, `dismissPopups()`, `uploadImages([{ref,url}])`, `setDocument(json)`, `componentCount()`. 각 함수는 브리지 콜백으로 결과를 보낸다. 스파이크의 스키마/매핑 규칙(`spike/findings.md` §3~4)을 그대로 따른다.
- 팝업 닫기: `#mainFrame` 문서에서 버튼 텍스트가 정확히 "취소"/"닫기"인 요소 클릭. 존재하지 않으면 그냥 통과.
- Reviewing 단계에서 WebView는 화면 전체(TopBar 없음). 상단에 얇은 InlineBanner "내용을 확인하고 오른쪽 위 '발행'을 눌러 주세요" + 나가기 버튼.

## 7. 세션 (FR-1)

- `LoginScreen`: 같은 설정의 WebView로 `https://nid.naver.com/nidlogin.login` 로드. URL이 `nid.naver.com` 밖(예: `www.naver.com`)으로 이동하면 성공으로 판정 → 같은 WebView로 `https://blog.naver.com/MyBlog.naver` 로드 → `blog.naver.com/{blogId}` 로 리다이렉트된 URL에서 blogId 추출(`BlogIdResolver`, 정규식 `^https://blog\.naver\.com/([A-Za-z0-9_-]+)$`) → `SettingsStore`에 저장 → 이전 목적지로 복귀.
- 2단계 인증/캡차는 WebView 안에서 사용자가 처리. 앱은 개입하지 않는다.
- 만료 판정: 발행 흐름에서 `nid.naver.com` 리다이렉트 감지. 홈 진입 시 별도 체크는 하지 않는다 (No More Loading).
- 로그아웃(관리자 화면): `CookieManager.removeAllCookies` + blogId 삭제.

## 8. 폴백 (FR-8)

- `FallbackScreen`: 제목 + 원인 한 문장(사용자 언어) + 버튼 3개(세로): "글 복사하고 블로그 앱 열기"(Primary), "다시 시도"(Weak), "관리자에게 알리기"(Weak).
  - 복사 텍스트: 제목 줄 + 빈 줄 + 문단들(서식 제거) + 사진 위치는 `[사진 1]` 표기. `ClipboardManager`.
  - 블로그 앱: `packageManager.getLaunchIntentForPackage("com.nhn.android.blog")` 없으면 Play 스토어 링크.
  - 관리자에게 알리기(SP1 최소 구현): 실패 로그 요약 텍스트로 `ACTION_SEND` 공유 시트. 본격 구현은 SP3.
- `FailureLog(at, stage, message, detail(url, jsError, editorIds), appVersion)` Room 저장. 관리자 화면에서 목록/상세.

## 9. 이력 (FR-10.1, 10.3)

- `PublishHistory(id, title, logNo, url, publishedAt, imageCount)`. url = `https://blog.naver.com/{blogId}/{logNo}`.
- `HistoryScreen`: ListRow 목록(제목 / "8월 28일 오후 3:12 · 사진 5장"), 탭하면 `ACTION_VIEW`로 브라우저. 비어 있으면 빈 상태 문구.

## 10. 화면 (SP1)

| 화면 | 내용 |
|---|---|
| Home | `title1` "오늘은 어떤 이야기를 올릴까요?" / BottomCta "새 글 쓰기"(SP1에서는 TestCompose로) / ListRow "발행한 글" / 우상단 톱니 → FailureLog. 미완료 PendingJob 있으면 InlineBanner. 로그인·blogId 없으면 "새 글 쓰기" 탭 시 Login으로. |
| Login | TopBar(뒤로) + 안내 한 줄 + WebView. |
| TestCompose (임시) | 제목 TextField, 본문 여러 줄 TextField(빈 줄로 문단 구분), PhotoGrid(Photo Picker), BottomCta "발행하러 가기". SP2에서 제거. |
| Publish | ProgressScreen 오버레이(단계 문장 + 진행 바) → Reviewing에서 WebView 노출. |
| Result | 성공: "발행했어요" + "확인"(Home) / 실패는 Fallback으로. |
| Fallback | §8. |
| History | §9. |
| FailureLog (관리자) | 밀도 높은 리스트, 상세 텍스트 복사. |

## 11. 테스트 전략

- 단위(JVM): `DocumentModelConverter`(고정 fixture와 구조 비교: 컴포넌트 순서, 서식 필드, 이미지 매핑 규칙), `PublishUrlParser`, `BlogIdResolver`, `PublishStateMachine`(모든 전이와 타임아웃/오류 경로), `FallbackTextRenderer`.
- Robolectric: Room DAO, `ImagePreparer`(리사이즈 결과 크기·회전), `SettingsStore`.
- 수동(에뮬레이터 → 태블릿): 전체 발행 흐름, 세션 만료 재개(쿠키 삭제 후 발행 시도), 폴백(기내 모드로 업로드 실패 유도).
- JS(`editor_bridge.js`)는 스파이크에서 검증된 코드를 함수화한 것이며 자동 테스트하지 않는다. 대신 각 함수가 브리지로 오류를 반드시 회신하도록 작성한다.

## 12. 배포

- `.github/workflows/release.yml`: `v*` 태그 푸시 시 `assembleRelease` → 서명(`secrets.KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) → `softprops/action-gh-release`로 APK 첨부. PR/푸시에는 `test` + `assembleDebug`.
- 서명 키는 로컬에서 `keytool`로 1회 생성해 Secrets에 base64로 등록 (README에 절차).
- R8 minify는 켜되 WebView JS 인터페이스 클래스는 keep.

## 13. 오류 처리 원칙

- 사용자 화면 문구는 디자인 가이드 §6. 원인 분류: `SessionExpired`(로그인 유도), `EditorNotReady`(네이버 페이지 문제/구조 변경 추정), `UploadFailed`(네트워크), `InjectFailed`(구조 변경 추정), `Timeout`.
- 모든 Failed는 FailureLog에 기술 상세를 남긴다. 사용자에겐 상세를 보여주지 않는다.
- 발행 자체를 자동화하지 않으므로 중복 발행 위험은 없다. 재시도는 항상 새 글쓰기 페이지에서 처음부터.

## 14. SP1 이후 인터페이스

- (2026-08-28 결정) SP2의 글쓰기는 **채팅 UI + 오른쪽 사이드 패널**이다(`docs/design-guide.md` §8). 따라서 발행 UI는 부모가 크기를 정하는 `PublishPanel` 컴포저블로 만들고, SP1의 `PublishScreen`은 이를 전체 화면으로 감싼 임시 래퍼다. `PublishViewModel`/상태 기계/WebView 엔진은 SP2에서 그대로 쓴다.
- SP2는 `PostContent`를 만들어 `PendingJobRepository`에 저장하고 같은 화면 안에서 `PublishPanel(viewModel = hiltViewModel(key = jobId))`을 연다. 부분 수정 시에는 새 `PostContent`로 재주입(`Injecting`부터 재실행)한다 — 이 재주입 이벤트는 SP2에서 상태 기계에 추가한다.
- TestCompose 화면은 SP2에서 제거되고 채팅 화면으로 대체된다.
