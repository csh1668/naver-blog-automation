# 블로그 도우미

네이버 블로그 글쓰기를 돕는 갤럭시 탭용 앱. 사진과 줄거리로 글을 만들고(SP2), 네이버 스마트에디터에 자동 입력한 뒤 사용자가 직접 발행한다.

## 개발
- Android Studio (AGP 9.3, JDK 17). `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- 설계: `docs/superpowers/specs/`, 디자인: `docs/design-guide.md`, 에디터 규칙: `spike/findings.md`
- 에뮬레이터로 확인할 때는 API 35 이미지를 사용한다 (`minSdk`가 33이라 API 33/34/35 모두 실행 가능하지만 API 35로 검증했다).
- 현재 앱의 "테스트 글 작성" 화면은 SP1 단계의 임시 화면이며, SP2에서 채팅형 UI로 교체될 예정이다.

## 릴리스
1. 최초 1회 서명 키 생성: `keytool -genkeypair -v -keystore release.jks -alias blogwriter -keyalg RSA -keysize 2048 -validity 10000`
2. GitHub Secrets 등록: `KEYSTORE_BASE64`(`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
3. `git tag v0.1.0 && git push origin v0.1.0` → Releases 에 `blogwriter-v0.1.0.apk` 첨부
4. 태블릿에서 Release 페이지의 APK 를 내려받아 설치 (출처를 알 수 없는 앱 허용 필요)

로컬 서명 빌드: 루트에 `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) 를 두고 `./gradlew :app:assembleRelease`.
