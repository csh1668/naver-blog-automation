# 배포·업데이트 설계 (FR-12) — 후속 소작업

- 일자: 2026-08-28. 근거: `요구사항.md` §5 3차. SP2와 독립이며 폴리시 패스 직후 구현한다.

## 1. 릴리스 본문 (FR-12.1)
- `.github/workflows/release.yml`의 `softprops/action-gh-release` 단계에 `body` 를 직접 구성한다:
  1행: `## 📥 다운로드: [blogwriter-<tag>.apk](https://github.com/<owner>/<repo>/releases/download/<tag>/blogwriter-<tag>.apk)`
  2행: 태블릿 설치 안내 한 줄("탭에서 이 링크를 눌러 내려받고, 알 수 없는 앱 설치를 허용한 뒤 여세요").
  그 아래: `generate_release_notes: true` 로 자동 생성되는 변경 내역(커밋/PR 제목 기반). `body` 와 `generate_release_notes` 를 함께 쓰면 본문이 위, 자동 노트가 아래에 붙는다.
- 커밋 메시지가 곧 변경 내역이므로 한국어 요약 제목을 유지한다.

## 2. 시작 시 업데이트 확인 (FR-12.2)
- `update/UpdateChecker.kt`: `GET https://api.github.com/repos/<owner>/<repo>/releases/latest` (OkHttp, 5초 제한, `Accept: application/vnd.github+json`, 인증 없음 — 공개 저장소 전제; 비공개면 SP2의 관리자 설정에 토큰 항목 추가). 응답의 `tag_name`(`v1.2.3`)을 `BuildConfig.VERSION_NAME`과 SemVer 비교. 결과 `UpdateInfo(tag, htmlUrl, apkUrl?)`.
- 호출 시점: 앱 시작 후 채팅 화면 진입 시마다(10분 안의 재진입은 생략 — DataStore에 마지막 확인 시각). 실패는 무시(로그만).
- UI: Home 상단 `InlineBanner(kind = Info)` "새 버전(v1.2.3)이 나왔어요 — 받으러 가기" → `ACTION_VIEW` 로 릴리스 페이지(`html_url`) 열기. 배너는 닫기 가능(같은 태그는 다시 표시하지 않음).
- 저장소 좌표: `BuildConfig.GITHUB_REPO = "csh1668/naver-blog-automation"`(가정 — 실제 저장소 생성 시 확정), `app/build.gradle.kts`의 `buildConfigField`.
- 테스트: SemVer 비교(`v0.1.0` < `v0.2.0`, `v0.1.0-rc1` 처리는 하지 않음 — 태그는 항상 `vMAJOR.MINOR.PATCH`), MockWebServer로 200/404/타임아웃, 10분 스로틀.
