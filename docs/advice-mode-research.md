# 조언 모드 사전 리서치 — LLM 블로그 조언은 정말 유효한가 (2026-08-29)

채팅 UI에 "조언 모드"(블로그 현황을 읽고 방향을 제시)를 넣기 전에, (1) 학술 근거 (2) 실제 운영 사례 (3) 앱이 데이터를 실제로 확보할 수 있는지를 조사했다.

## 한 줄 결론

- **글 개선 조언**(구조·가독성·문법·구체성)은 근거가 반복 재현됨 → 넣을 가치 있음. 단 효과는 초안 대필보다 작고(d≈0.2), 아첨·일반론·문체 동질화를 프롬프트로 눌러야 한다.
- **운영 조언**("이런 글을 더 쓰라", 발행 빈도, 제목)은 **실측 데이터를 넣어 줄 때만** 유효하다는 것이 국내외 사례의 공통 패턴. 데이터 없는 "주제 추천"은 일반론으로 끝난다.
- **성과 인과 주장**("이렇게 하면 조회수/순위 오른다")은 근거 없음. LLM 단독의 제목 성과 예측은 무작위 수준. 제품 카피·출력에서 약속하지 말 것.
- 데이터 확보는 **가능**: 글 목록/본문은 비인증 엔드포인트, 통계는 크리에이터 어드바이저 `/api/v6/*`를 로그인 WebView 안에서 `fetch`.

## 1. 학술 근거

### 강한 근거 (RCT)
| 연구 | 결과 | 주의 |
|---|---|---|
| Noy & Zhang 2023, *Science* (전문직 453명) | 시간 −40%, 블라인드 품질 +18%, 하위 능력자 이득 큼 | 대필 실험이지 "조언" 실험이 아님 |
| Dell'Acqua et al. 2023/25, *Org. Science* (BCG 758명) | 품질 +40% | 아이디어 다양성 뚜렷이 감소, 능력 밖 과제 오답 +19%p |
| Meyer et al. 2024 (고교생 459명, GPT 피드백) | 수정 품질 d=0.19(작음), 동기 d=0.36 | 대조군 "피드백 없음" |
| FeedbackWriter, CHI 2026 (354명) | AI 제안 → 사람이 걸러 전달 시 수정 품질 유의 상승 | human-in-the-loop 설계 |
| 메타분석 2026 (46편) g=0.60; AI vs 사람 피드백 메타 2025 g=0.25 [−0.11, 0.60] | 사람 피드백과 통계적으로 비슷 | 교육 맥락 위주 |

### 혼재/약한 근거
- 피드백 "품질 점수"가 높아도 수용률·결과 개선은 사람 피드백과 동등하거나 낮음 (IJETHE 2026, Steiss 2024).
- SEO: Semrush 42k 포스트(1위 80%가 사람 작성), Search Engine Land 16개월(AI 글 3~6개월 후 상위 100 잔존 3%) — 관찰연구, "AI 조언 받아 사람이 쓴 글"의 효과는 측정된 바 없음.
- 제목: Chartbeat A/B 승률 AI 27% vs 사람 26%(차이 없음). Upworthy 17,681건 검증: **LLM 단독 승자 예측은 거의 무작위**, 밴딧과 결합해야 이득 (Marketing Science 2025).
- 분석 → 전략 추천: InsightBench(ICLR 2025) — 기술 통계는 잘 찾지만 **진단·처방으로 갈수록 급락**, 약한 추세(기울기 <0.1)는 놓침. "LLM 전략 추천을 실행해 성과가 올랐다"는 실증 없음.

### 실패 모드
1. 동질화 — 공동 작성 시 저자 간 유사도↑ (Padmakumar & He, ICLR 2024)
2. 아첨 — 반박하면 입장 번복, 오답에도 칭찬 (SycEval 2025)
3. 일반론 피드백 — 수용률 낮음
4. 역량 저하 — 가드레일 없는 사용은 AI 제거 후 성적 하락, 힌트만 주는 설계는 완화 (Bastani 2025, PNAS)
5. 분석의 얕음 — 자신감 있는 헛소리
6. 성과 예측 불가

## 2. 실제 사례

### 네이버
- **공식 입장(2024.2, naver_search 블로그)**: AI 작성 여부 자체는 문제 없음. 제재 대상은 복사·키워드 반복·문맥 끊긴 자동문장·**AI로 같은 내용 반복 대량 업로드**·짜깁기.
- 2026: AI 브리핑이 쿼리 ~20% 처리, 인용의 ~70%가 블로그/카페, 인용 보상 "네이버 메이트" 베타. 반면 ZDNet(2026.8): 블로거 애드포스트 수익 ~40% 감소.
- 부정: 하루 수십 개 대량 발행 → 어뷰징 감지. "무색무취 구조·채우기 텍스트·팩트체크 부재·경험 신호 없음"이 노출 실패 공통점.
- 긍정(조언형): 네이버 키워드 조회수·상위글 제목을 **입력 데이터로 넣고** 제목/구조 조언받은 GPTs 사례들 — "대부분 노출 잘 됨", 단 방문자 수치는 전부 미공개. 아이보스 칼럼: 맨몸 "키워드 추천해줘"는 무의미, 조회수 포함 리스트 넣어야 유효.

### 글로벌
- 벤더 자기보고 긍정: Surfer +65%/3개월, Clearscope Close +40%, MarketMuse 갭 분석 2~3배, Jasper 발행량 +113%.
- 독립 측정: Ahrefs 33만 페이지 — AI 사용과 순위 상관 0.011(처벌도 보상도 없음), 단 AI 비중 ≥80% 페이지는 노출 2~3배 낮음. Lily Ray 220+ 사이트 — AI 콘텐츠 사이트 54%가 정점 대비 30%+ 손실, 6~12개월 급등 → 1년 내 반납.
- 오류: CNET AI 기사 77건 중 41건 정정. NP Digital 설문: 마케터 36.5%가 AI 오류를 공개 게시.
- "통계와 대화" 제품: GA4 Ask Advisor — 세그먼트 비교·계산 지표·추적 오류 진단은 유효, 초기엔 "상위 지표 조회 도구" 취급. GA4 자동 인사이트는 계절성 오탐. Surfer Grow Flow(주간 과제 + 임팩트 점수)는 초보에게 "기본적이지만 건전".

### 패턴
| 유효 | 무효/역효과 |
|---|---|
| 실측 데이터 주입 → 구체 액션(제목 후보, 갱신 대상 글, 내부링크) | 데이터 없는 주제 추천 → 일반론 |
| 기존 글 최적화·갭 채우기 | 대량 발행·템플릿 → 단기 상승 후 붕괴, 네이버 명시 제재 |
| 우선순위 붙은 소수 과제(주 3개 + 근거 지표) | AI가 제시하는 수치·근거의 환각 |

## 3. 데이터 확보 가능성 (실측)

| 데이터 | 방법 | 인증 | 취약성 |
|---|---|---|---|
| 글 목록(logNo, 제목, 댓글/공감 수, 요약) | `blog.naver.com/PostTitleListAsync.naver?blogId=&countPerPage=30&currentPage=1&categoryNo=0&parentCategoryNo=0&listType=post` 또는 `m.blog.naver.com/api/blogs/{id}/post-list?categoryNo=0&itemCount=30&page=1` (**Referer: https://m.blog.naver.com/{id} 필수**) | 공개글은 불필요, 비공개/이웃공개는 쿠키 | 낮음 |
| 글 본문 | `m.blog.naver.com/PostView.naver?blogId=&logNo=` → `div.se-main-container` | 공개글 불필요 | 낮음 |
| 최근 5일 방문자 | `blog.naver.com/NVisitorgp4Ajax.naver?blogId=` (XML) | 불필요, 숨김 설정 시 204 | 낮음 |
| 이웃 수 | `m.blog.naver.com/api/blogs/{id}/buddies/total-count` | 로그인+본인 | 낮음~중간 |
| 조회수 추이·인기글·유입경로·유입검색어·성별연령·시간대 | **크리에이터 어드바이저** `creator-advisor.naver.com/api/v6/*` (`service=naver_blog&channelId=&interval=day|week|month&date=`) — `/dashboard/report`, `/integrated-analysis/d1-ranks`, `/inflow-analysis/referrer-query-rank`, `/integrated-analysis/follower-count`, `/home/soaring-contents` 등 | 로그인 + **HMAC 서명 헤더(X-CA-Nonce/Ts/Sig, 키는 `__ca_key` 쿠키)** → WebView 안에서 페이지의 `fetch`를 그대로 쓰는 게 안전 | 중간 |
| blog.stat.naver.com 통계 | XHR 미공개, 로그인 후 리버싱 필요 | | 높음 → 어드바이저로 대체 |

- 공식 Open API에는 내 글 목록·통계 없음(블로그 검색·writePost·listCategory뿐).
- 약관: "사전 허락 없는 자동화 수단으로 … 게시물 수집" 금지 문구 있음. 본인이 로그인한 WebView에서 본인 데이터를 사용자 트리거로 소량 읽는 것은 상대적으로 리스크 작음. 리스크는 계정 제한이므로 **버튼당 1회·캐시·낮은 빈도** 원칙.
- 다음 스파이크: 실제 계정으로 `/api/v6/accounts/channels` → `/dashboard/report` 응답 스키마 확인.

## 4. 설계에 대한 시사점

1. **조언은 데이터 위에서만.** 조언 모드 첫 턴에 통계+글 목록을 자동 수집해 컨텍스트로 넣고, 수집 실패 시 "운영 조언"은 내지 않고 "글 개선 조언"만 하도록 제한.
2. **출력 형식**: "이번 주 과제 최대 3개 — 각각 근거 지표 + 예상 효과(가설임을 명시)". 임계 이하 변화는 "판단 보류".
3. **프롬프트 가드**: 비판 우선·근거 필수, 사용자가 반박해도 근거 없이 입장을 바꾸지 말 것, 사용자 문체는 base로 유지(관용구 교체 금지), 대량 발행/같은 주제 변주 추천 금지, 발행 빈도 상한 하루 1~3개.
4. **말하지 말 것**: "이렇게 하면 조회수가 오른다" 같은 인과 약속. 제목 A/B 승자 예측.
5. **경험 신호 체크리스트**(1인칭 경험, 직접 찍은 사진, 구체 수치)를 글 개선 조언의 기본 항목으로 — 네이버 D.I.A./신뢰도 신호와 일치.

## 출처
- Noy & Zhang: https://www.science.org/doi/10.1126/science.adh2586
- Dell'Acqua: https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4573321
- Meyer 2024: https://www.sciencedirect.com/science/article/pii/S2666920X23000784
- FeedbackWriter CHI 2026: https://arxiv.org/pdf/2602.16820
- AI vs 사람 피드백 메타: https://www.tandfonline.com/doi/full/10.1080/01443410.2025.2553639
- Upworthy/LOLA: https://pubsonline.informs.org/doi/10.1287/mksc.2024.0990
- InsightBench: https://proceedings.iclr.cc/paper_files/paper/2025/file/0dfe31d6e703e138d46a7d2fced38b7c-Paper-Conference.pdf
- Padmakumar & He: https://arxiv.org/abs/2309.05196 · Bastani PNAS: https://www.pnas.org/doi/10.1073/pnas.2422633122
- 네이버 검색 공식 입장(2024.2): https://blog.naver.com/naver_search/223367781299
- ZDNet 2026.8: https://zdnet.co.kr/view/?no=20260805173711
- 네이버 노출 실패 패턴: https://moneyroan.com/naver-ai-blog-not-exposed-reason-2026/
- GPTs 키워드 조언 사례: https://www.gpters.org/marketing/post/naver-exposure-gpt-keyword-INXk2mWKbup7hsq · 아이보스: https://www.i-boss.co.kr/ab-74668-3761
- Ahrefs: https://ahrefs.com/blog/google-doesnt-punish-ai-content/ · Lily Ray: https://lilyraynyc.substack.com/p/it-works-until-it-doesnt-ai-content-risks
- GA4 Ask Advisor: https://www.gaoptimizer.com/blog/google-analytics-advisor-ai-first-impresions/
- 통계 개편 항목: https://www.etnews.com/20181105000287 · 어드바이저 도움말: https://help.naver.com/service/23038/category/bookmark
- 방문자 XML: https://github.com/krta2/NaverBlogVisitorCntCrawler · 네이버 약관: https://policy.naver.com/rules/service.html
