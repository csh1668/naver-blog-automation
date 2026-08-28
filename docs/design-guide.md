# 디자인 가이드 — Toss-like

- 버전: v0.1 (2026-08-28)
- 대상: 블로그 글쓰기 앱 (갤럭시 탭 S9, 가로/세로 모두)
- 참고: 토스 제품 원칙·UX 원칙, TDS Mobile 컴포넌트 구조, TDS 컬러 시스템 업데이트 글

이 문서는 요구사항 명세가 "별도 문서"로 넘긴 디자인/UX 방향을 정한다. 코드에서는 `ui/theme/` 의 토큰과 `ui/components/` 의 공용 컴포넌트로 구현하며, 화면 코드는 이 토큰과 컴포넌트만 사용한다.

---

## 1. 원칙 (토스에서 가져온 것 → 이 앱에서의 의미)

| 토스 원칙 | 이 앱에서의 적용 |
|---|---|
| **One Thing per One Page** — 한 화면 한 메시지 | 화면마다 큰 제목 한 줄 + 하단 CTA 하나. 글쓰기 흐름은 "사진 고르기 → 줄거리 말하기 → 글 확인 → 발행" 4단계로 화면을 쪼갠다. 한 화면에 입력 두 종류를 두지 않는다. |
| **Tap & Scroll** — 누르기와 스크롤만으로 핵심 플로우 완성 | 텍스트 입력은 줄거리 한 곳뿐이고 음성 입력을 기본 제안. 나머지는 탭. 드래그·롱프레스는 사진 순서 변경에만 쓰고, 대체 경로(위/아래 버튼)를 함께 둔다. |
| **Easy to Answer** — 모든 질문에 3초 안에 답할 수 있게 | 선택지는 최대 3개(제목 후보 2~3개). 질문 문구는 "어떤 제목이 좋으세요?"처럼 대화체 한 문장. |
| **Value First, Cost Later** | 온보딩에서 로그인·설정을 먼저 요구하지 않고, 첫 화면에서 바로 "새 글 쓰기"를 보여준 뒤 필요한 시점에 로그인을 요청한다. |
| **No More Loading** | 기다려야 할 때는 스피너 대신 "사진 올리는 중 2/5" 같은 진행 문장 + 진행 바. 글 생성 중에는 무엇을 하는지 단계별 문장으로 보여준다. |
| **Casual Concept / Less Policy** | "API 키", "세션 만료" 같은 말은 사용자 화면에 쓰지 않는다. "네이버에 다시 로그인해 주세요"처럼 행동으로 말한다. 기술 용어는 관리자 화면에서만. |
| **Sleek Experience** | 화면 전환은 단순 페이드/슬라이드 200ms. 성공 시에는 체크 애니메이션 한 번. 장식 애니메이션 없음. |

추가 원칙 (사용자 특성: 60대, 태블릿):
- **큰 것이 기본**: 본문 17sp 이상, 터치 타겟 최소 56dp, 주요 CTA 높이 60dp.
- **되돌릴 수 있게**: 파괴적 동작(삭제)은 항상 확인 시트. 발행은 사용자가 에디터에서 직접 누른다.
- **막히면 사람에게**: 오류 화면에는 항상 "관리자에게 알리기" 버튼이 있다.

---

## 2. 컬러 토큰

TDS와 같이 **Base 팔레트 → Semantic 토큰** 두 층으로 관리한다. 화면 코드는 Semantic 토큰만 쓴다. 라이트 모드를 기본으로 설계하고 다크 모드는 지원 범위 밖 (설정으로 강제 라이트).

### Base 팔레트

| 이름 | Hex | 비고 |
|---|---|---|
| blue500 (Toss Blue) | `#3182F6` | 브랜드/Primary |
| blue600 | `#1B64DA` | pressed |
| blue100 | `#E8F3FF` | weak 배경 |
| grey50 | `#F9FAFB` | 화면 배경(섹션) |
| grey100 | `#F2F4F6` | 카드/리스트 구분 배경 |
| grey200 | `#E5E8EB` | 보더 |
| grey300 | `#D1D6DB` | 비활성 보더 |
| grey400 | `#B0B8C1` | 플레이스홀더 |
| grey500 | `#8B95A1` | 보조 텍스트 |
| grey600 | `#6B7684` | 부제 |
| grey700 | `#4E5968` | 본문 보조 |
| grey800 | `#333D4B` | 본문 |
| grey900 | `#191F28` | 제목 |
| red500 | `#F04452` | Danger |
| red100 | `#FFEEEE` | Danger weak 배경 |
| green500 | `#03B26C` | 성공 |
| green100 | `#E5F7EF` | 성공 weak 배경 |
| orange500 | `#FF9E2C` | 경고 |
| white | `#FFFFFF` | |

### Semantic 토큰 (`AppColors`)

| 토큰 | 값 | 용도 |
|---|---|---|
| `background` | white | 화면 기본 배경 |
| `backgroundAlt` | grey50 | 리스트 화면 배경, 섹션 구분 |
| `surface` | white | 카드 |
| `surfaceWeak` | grey100 | 입력 필드 배경, 비활성 카드 |
| `border` | grey200 | 구분선 |
| `textPrimary` | grey900 | 제목, 본문 |
| `textSecondary` | grey600 | 설명 |
| `textTertiary` | grey400 | 플레이스홀더, 캡션 |
| `textOnBrand` | white | Primary 버튼 텍스트 |
| `fillBrand` | blue500 | Primary 버튼, 진행 바, 링크 |
| `fillBrandPressed` | blue600 | |
| `fillBrandWeak` | blue100 | Weak 버튼 배경, 선택 상태 |
| `fillDanger` | red500 | 삭제, 오류 |
| `fillDangerWeak` | red100 | 오류 배너 배경 |
| `fillSuccess` | green500 | 완료 체크 |
| `fillSuccessWeak` | green100 | |
| `fillWarning` | orange500 | 폴백 안내 |

---

## 3. 타이포그래피

폰트: 시스템 기본 산세리프(One UI의 SamsungOne 또는 Roboto/Noto Sans KR). Toss Product Sans는 비공개 서체이므로 쓰지 않는다. 숫자는 `FontFeature "tnum"` 로 고정폭.

| 토큰 | 크기/굵기/줄간격 | 용도 |
|---|---|---|
| `display` | 32sp / Bold / 40sp | 온보딩·완료 화면의 한 줄 메시지 |
| `title1` | 26sp / Bold / 34sp | 화면 제목 ("어떤 사진으로 쓸까요?") |
| `title2` | 22sp / Bold / 30sp | 섹션 제목, 카드 제목 |
| `title3` | 19sp / SemiBold / 26sp | 리스트 항목 제목 |
| `body1` | 17sp / Regular / 26sp | 본문, 설명 (기본) |
| `body2` | 15sp / Regular / 22sp | 보조 설명 |
| `caption` | 13sp / Regular / 18sp | 시간, 메타 정보 |
| `button` | 18sp / SemiBold | CTA 버튼 |

태블릿 가로 모드에서는 콘텐츠 최대 폭 720dp로 가운데 정렬 (글줄이 너무 길어지지 않게).

---

## 4. 스페이싱과 형태

- 스페이싱 스케일: 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48dp. 화면 좌우 여백 **24dp** (태블릿), 섹션 간 **32dp**, 리스트 행 내부 **16dp**.
- 라운드: 버튼·입력 필드 **16dp**, 카드 **20dp**, 바텀시트 상단 **24dp**, 썸네일 **12dp**, 칩 **999dp**.
- 그림자: 쓰지 않는다. 카드는 배경색 차이(`surface` on `backgroundAlt`) 또는 1dp `border`로 구분.
- 아이콘: Material Symbols Rounded, 24dp(리스트) / 28dp(버튼 내).

---

## 5. 공용 컴포넌트 (`ui/components/`)

| 컴포넌트 | 규칙 |
|---|---|
| **TopBar** | 높이 56dp, 왼쪽 뒤로가기(← 아이콘, 48dp 타겟), 제목 없음 또는 `title3`. 화면 제목은 TopBar가 아니라 본문 상단의 `title1`로 크게 보여준다 (토스 방식). |
| **BottomCta** | 화면 하단 고정. 좌우 24dp, 하단 24dp(+시스템 바 인셋). 높이 60dp, `fillBrand`, 라운드 16dp, `button` 텍스트. 상태: enabled / disabled(알파 0.4) / loading(텍스트 유지 + 우측 스피너, 폭 고정). 두 개일 때는 세로 배치: 위 Primary(fill) / 아래 Weak(fillBrandWeak + 파란 텍스트). 가로 배치는 쓰지 않는다. |
| **WeakButton** | `fillBrandWeak` 배경 + `fillBrand` 텍스트. 보조 행동 ("다시 쓰기", "건너뛰기"). |
| **DangerButton** | 삭제·초기화. 항상 ConfirmSheet 뒤에서만. |
| **ListRow** | 높이 최소 72dp. 왼쪽 썸네일/아이콘(48dp), 가운데 제목(`title3`) + 부제(`body2`, `textSecondary`), 오른쪽 chevron. 전체 행이 탭 타겟. 구분선 없이 12dp 간격 + `surfaceWeak` 카드형. |
| **StepHeader** | 글쓰기 4단계 흐름의 상단 표시. "1/4" 텍스트 + 얇은 진행 바(`fillBrand`, 4dp). |
| **ProgressScreen** | 전체 화면. 상단 큰 일러스트 없이 `title1` 문장("사진을 올리고 있어요") + 진행 바 + `body2` 세부("3장 중 2장"). 취소 버튼은 Weak. |
| **ResultScreen** | 성공: `fillSuccess` 체크 아이콘(72dp) + `display` 메시지("발행했어요") + BottomCta("확인"). 실패: `fillDanger` 아이콘 + 원인 한 문장 + 두 버튼(다시 시도 / 관리자에게 알리기). |
| **ConfirmSheet** | 바텀시트. `title2` 질문 + `body1` 설명 + 세로 두 버튼. 바깥 탭으로 닫힘. |
| **Toast** | 하단 CTA 위 16dp, `grey900` 배경 90% + 흰 텍스트 `body2`, 2초. 오류에는 쓰지 않는다(오류는 배너 또는 ResultScreen). |
| **InlineBanner** | 화면 상단 카드. `fillDangerWeak`/`fillSuccessWeak` 배경, 아이콘 + `body2`. "네이버 로그인이 필요해요 →" 같은 상태 안내. |
| **TextField** | `surfaceWeak` 배경, 라운드 16dp, 높이 56dp(한 줄) / 자동(여러 줄). 포커스 시 `fillBrand` 1.5dp 보더. 라벨은 필드 위 `body2`. 오류는 필드 아래 `caption` 빨강. |
| **PhotoGrid** | 3열(세로)/5열(가로), 정사각 썸네일 라운드 12dp, 선택 순서 배지(`fillBrand` 원 + 흰 숫자). |

---

## 6. 문구 (카피) 규칙

- 존댓말 "~해요"체. 명령문 대신 제안문: "사진을 골라 주세요" → "어떤 사진으로 쓸까요?"
- 버튼은 동사로 끝: "다음", "이 제목으로", "발행하러 가기", "다시 시도".
- 오류 문장 구조: [무슨 일] + [어떻게 하면 되는지]. 예: "네이버 로그인이 풀렸어요. 다시 로그인하면 쓰던 글을 이어서 올릴게요."
- 숫자는 아라비아 숫자, 단위 붙여쓰기: "사진 5장", "2분 전".
- 기술 용어(API, 세션, 토큰, HTML, WebView)는 사용자 화면 금지.

---

## 7. 화면 구조 규칙

- 사용자 화면 계층: **홈 → (글쓰기 4단계 | 이력 | 초안)**. 깊이 2를 넘지 않는다.
- 관리자 화면은 홈 우상단 톱니 아이콘 → PIN → 설정. 사용자 화면과 같은 토큰을 쓰되 기술 용어 허용, 밀도 높은 리스트 허용.
- 뒤로가기: 입력이 있는 화면에서는 자동 저장하므로 확인 없이 나간다 (FR-4.4, FR-6.4).
- 가로/세로 모두 지원. 글쓰기 단계 화면은 가로에서 좌(입력)/우(미리보기) 2단이 아니라 **같은 단일 컬럼을 720dp 폭으로 가운데 정렬** — 한 화면 한 메시지 유지.
