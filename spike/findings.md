# FR-7 스파이크 결과 — 스마트에디터 ONE WebView 자동 입력

- 일자: 2026-08-28
- 결론: **가능.** 합성 paste / `onShowFileChooser` 대신 에디터 내부 API(`setDocumentData` + 이미지 업로드 서비스)를 사용한다.
- 이 폴더의 코드(`*.js`, `android/`)는 전부 throwaway. 본 프로젝트에 복사하지 말고 아래 규칙만 가져간다.

## 1. 검증된 사실

| 항목 | 결과 |
|---|---|
| 데스크톱 Chrome에서 documentModel 주입 | 제목·서식 문단·이미지 2장·인용구 모두 렌더링 (v5) |
| Android WebView(에뮬레이터, API 32)에서 동일 주입 | 성공. 업로드 2장 + 주입에 약 2초 |
| 모바일 리다이렉트 | 데스크톱 UA 설정 시 `m.blog.naver.com`으로 가지 않고 PC 에디터 로드. `?viewType=pc`는 보조 |
| 세션 영속 (FR-1.1) | `CookieManager` 기본 동작으로 앱 재시작 후에도 로그인 유지 |
| 발행 URL (FR-7.6) | 발행 직후 `https://blog.naver.com/PostView.naver?blogId={id}&Redirect=View&logNo={logNo}&…&isAfterWrite=true&…` |
| 비공개 발행 | 발행 설정 패널에서 "비공개" 선택 가능 → 테스트에 사용 |

## 2. 에디터 구조

```
https://blog.naver.com/{blogId}?Redirect=Write            ← 앱이 로드하는 URL
  └─ iframe#mainFrame  (같은 origin: blog.naver.com/PostWriteForm.naver?blogId=…)
       └─ window.SmartEditor._editors.blogpc001           ← 에디터 인스턴스
```

- 로그인 안 된 상태면 `nid.naver.com/nidlogin.login`으로 리다이렉트됨 → 세션 만료 감지(FR-1.3)에 사용.
- 에디터 진입 시 뜰 수 있는 팝업: "작성 중인 글이 있습니다"(확인/취소), 도움말("시작하기"/"닫기"). 자동 닫기 필요.
- 발행 버튼: `button.publish_btn__XXXX` (해시 클래스) → 텍스트 `발행`으로 찾는다. 발행은 사용자가 직접 탭.
- JS 접근: `document.querySelector('#mainFrame').contentWindow.SmartEditor._editors.blogpc001`

주요 메서드: `getDocumentData()`, `setDocumentData(doc)`, `getDocumentTitle()`, `setDocumentTitle()`, `isEmptyDocumentContent()`, `validateAsync()`, `getComponentsByCtype()`.

## 3. documentModel 스키마 (getDocumentData 출력 기준)

```jsonc
{ "document": { "version": "2.10.2", "theme": "default", "language": "ko-KR", "id": "<기존 문서 id 재사용>", "components": [ … ] }, "documentId": "" }
```

모든 객체는 `id: "SE-<uuid>"` 와 `"@ctype"` 를 가진다.

| 컴포넌트 | 형태 |
|---|---|
| 제목 | `{ "@ctype":"documentTitle", layout:"default", title:[paragraph], subTitle:null, align:"left" }` |
| 본문 | `{ "@ctype":"text", layout:"default", value:[paragraph, …] }` |
| 인용구 | `{ "@ctype":"quotation", layout:"default", value:[paragraph], source:[paragraph] }` |
| 이미지 | 아래 4절 |

paragraph:
```jsonc
{ "@ctype":"paragraph", id, nodes:[textNode…],
  style:{ "@ctype":"paragraphStyle", lineHeight:1.7,
          align:"center",                                   // 선택
          list:{ "@ctype":"paragraphListStyle", type:"bullet"|"decimal", level:0 } } }  // 선택
```

textNode:
```jsonc
{ "@ctype":"textNode", id, value:"…",
  style:{ "@ctype":"nodeStyle", fontFamily:"nanumsquare", fontSizeCode:"fs19",
          bold:true, fontColor:"#ff0010", backgroundColor:"#ffd300" } }   // 각각 선택
```
- 글자 크기: `fontSizeCode` — 본문 기본 `fs19`, 소제목 `fs28` (에디터 UI의 크기 목록과 대응).
- 제목/인용구의 textNode는 style 없이도 동작.

## 4. 이미지 업로드

```js
const svc = editor._videoUploadService._imageUploadService;
const list = svc.createSourceList(ids /* string[] */, files /* File[] */);   // [{id, source}]
const pending = await svc.uploadImagesFromFiles(list);   // Promise<Promise[]>  ← 두 번 await 필요
const results = await Promise.all(pending);              // [{code:"SUCCESS", response:{…}}]
```

응답 `response` 필드: `url`(경로+파일명), `path`(경로만), `fileName`, `width`, `height`, `fileSize`, `thumbnail`, `imageType`, `domain`("https://blogfiles.pstatic.net").

image 컴포넌트 매핑 (**`path`에는 응답의 `url`을 쓴다** — `path`를 쓰면 404 "존재하지 않는 이미지"):
```jsonc
{ "@ctype":"image", id, layout:"default",
  src: domain + response.url + "?type=w1",
  path: response.url, domain: response.domain, internalResource:true,
  represent: <첫 이미지만 true>,
  fileSize: response.fileSize, fileName: response.fileName,
  originalWidth: response.width, originalHeight: response.height,
  width: min(693, originalWidth), height: <비율 유지>, widthPercentage:0,
  format:"normal", displayFormat:"normal", imageLoaded:true, contentMode:"fit",
  origin:{ "@ctype":"imageOrigin", srcFrom:"local" }, ai:false }
```
- 실패 시 `{code:"FAIL", response:{code:"CLIENT"|"SERVER_ERROR"|…}}` 로 reject.
- 업로더는 `File` 외에 base64 객체(`_uploadImageByBASE64`)도 받지만, Android→JS는 data URL → `fetch().blob()` → `File` 로 충분.
- 파일명은 ASCII(`img_001.jpg`)로 정규화한다 (한글 파일명은 path에 퍼센트 인코딩되어 들어감).
- 제한: `maxImageUploadSize` 20MB, 업로드 전 앱에서 리사이즈(긴 변 1600px 권장).

## 5. Android WebView 설정

- `settings.userAgentString` = 데스크톱 Chrome UA (필수), `javaScriptEnabled`, `domStorageEnabled`
- `CookieManager.setAcceptThirdPartyCookies(webView, true)`, `onPageFinished`에서 `flush()`
- `addJavascriptInterface(bridge, "AndroidBridge")` 로 완료/오류 회신
- `evaluateJavascript(script)` — 스크립트는 top frame에서 실행되고 `#mainFrame.contentWindow`로 에디터 접근
- 에디터 준비 판정: `!!frame?.contentWindow?.SmartEditor?._editors?.blogpc001` 폴링
- 화면: `Redirect=Write` 로드 → `categoryNo=…` 로 히스토리 갱신 → `pageFinished` 후 에디터 폴링

## 6. 남은 리스크 / 미검증

- 네이버가 에디터 번들을 바꾸면 내부 API 이름이 바뀔 수 있음 (C-3). `_videoUploadService._imageUploadService` 경로가 특히 취약 → 실패 시 FR-8 폴백.
- 2단계 인증: 새 기기 로그인 시 뜰 수 있음. WebView 안에서 사용자가 직접 처리.
- 팝업 자동 닫기, 카테고리 선택, 태그 입력은 미검증 (필요 시 documentModel 외부 — 발행 패널에서 사용자가 처리 가능).
- 실제 사진(JPEG, 수 MB)의 업로드 시간은 태블릿 실기기에서 측정 필요.
